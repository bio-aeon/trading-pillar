package st.btu.trading.pillar.services.commands

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.charts.EquityCurveImage
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGateway}
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.strategy.{
  ParameterizedStrategy,
  Strategy,
  StrategyId,
  StrategyRegistry
}
import st.btu.trading.pillar.strategy.backtest.*
import tofu.logging.Logging
import tofu.syntax.logging.*

import scala.util.Try

// Single-account app for now: the account (and therefore which watchlist /watchlist add lands in)
// isn't threaded through here; the suggestion in the NotEnoughBars reply is purely informational.
final class BacktestHandler[F[_]: Clock: Logging](
  tgGateway: TgGateway[F],
  registry: StrategyRegistry[F],
  service: BacktestService[F],
  marketDataSources: MarketDataSources[F],
  defaults: BacktestConfig
)(implicit F: MonadThrow[F])
    extends CommandHandler[F] {

  private val DefaultYears = 2
  private val ParamsPrefix = "params="

  def command: Command = Command.Backtest

  def usage: String =
    "/backtest strategyId SYM[,SYM2,...] (years, default 2) (params=key:value,key:value)"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case strategyIdRaw :: symRaw :: rest =>
        val strategyId = StrategyId(strategyIdRaw)
        val (paramsToken, remaining) = rest.partition(_.toLowerCase.startsWith(ParamsPrefix))
        val years = remaining.headOption
          .flatMap(s => Try(s.toInt).toOption)
          .filter(_ > 0)
          .getOrElse(DefaultYears)
        if (symRaw.contains(","))
          runPortfolioOrReject(chatId, strategyId, symRaw, years, paramsToken)
        else {
          val ticker = Ticker(symRaw.toUpperCase)
          paramsToken match {
            case Nil => runBacktest(chatId, strategyId, ticker, years)
            case token :: _ => runWithParams(chatId, strategyId, ticker, years, token)
          }
        }
      case _ => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  // A comma in the ticker argument means a portfolio backtest (BacktestService.runPortfolio);
  // params= is a single-strategy, single-ticker ad hoc rebuild (ParameterizedStrategy.withParameters)
  // and isn't supported together with it.
  private def runPortfolioOrReject(
    chatId: tg.ChatId,
    strategyId: StrategyId,
    symRaw: String,
    years: Int,
    paramsToken: List[String]
  ): F[Unit] =
    paramsToken match {
      case Nil =>
        parseTickers(symRaw) match {
          case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
          case tickers => runPortfolioBacktest(chatId, strategyId, tickers, years)
        }
      case _ :: _ =>
        Replies.reply(tgGateway, chatId, "params= is not supported with multiple tickers.")
    }

  private def parseTickers(symRaw: String): List[Ticker] =
    symRaw.split(",").toList.map(_.trim).filter(_.nonEmpty).map(s => Ticker(s.toUpperCase))

  private def runBacktest(
    chatId: tg.ChatId,
    strategyId: StrategyId,
    ticker: Ticker,
    years: Int
  ): F[Unit] =
    finish(
      chatId,
      ticker.value,
      for {
        today <- Replies.todayUtc[F]
        from = today.minusYears(years.toLong)
        source <- marketDataSources.forTicker(ticker)
        run <- service.run(strategyId, ticker, source.source, from, today, defaults)
      } yield run
    )

  private def runPortfolioBacktest(
    chatId: tg.ChatId,
    strategyId: StrategyId,
    tickers: List[Ticker],
    years: Int
  ): F[Unit] =
    finish(
      chatId,
      tickers.map(_.value).mkString(","),
      for {
        today <- Replies.todayUtc[F]
        from = today.minusYears(years.toLong)
        source <- marketDataSources.forTicker(tickers.head)
        run <- service.runPortfolio(strategyId, tickers, source.source, from, today, defaults)
      } yield run
    )

  private def runWithParams(
    chatId: tg.ChatId,
    strategyId: StrategyId,
    ticker: Ticker,
    years: Int,
    paramsToken: String
  ): F[Unit] =
    parseParams(paramsToken) match {
      case Left(_) => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case Right(overrides) =>
        registry.get(strategyId).flatMap {
          case None =>
            Replies.reply(
              tgGateway,
              chatId,
              s"Unknown strategy: ${strategyId.value}. Try /strategies for the list."
            )
          case Some(p: ParameterizedStrategy) =>
            p.withParameters(overrides) match {
              case Left(msg) => Replies.reply(tgGateway, chatId, s"Invalid parameters: $msg")
              case Right(strategy) => runAdHoc(chatId, strategy, ticker, years)
            }
          case Some(_) =>
            Replies.reply(
              tgGateway,
              chatId,
              s"Strategy ${strategyId.value} does not support parameters."
            )
        }
    }

  private def runAdHoc(chatId: tg.ChatId, strategy: Strategy, ticker: Ticker, years: Int): F[Unit] =
    finish(
      chatId,
      ticker.value,
      for {
        today <- Replies.todayUtc[F]
        from = today.minusYears(years.toLong)
        source <- marketDataSources.forTicker(ticker)
        run <- service.runStrategy(strategy, ticker, source.source, from, today, defaults)
      } yield run
    )

  private def finish(chatId: tg.ChatId, tickerLabel: String, attempt: F[BacktestRun]): F[Unit] =
    attempt
      .flatMap(run =>
        Replies.reply(tgGateway, chatId, formatSuccess(run)) >> sendEquityCurveImage(chatId, run)
      )
      .recoverWith {
        case BacktestServiceError.StrategyNotFound(id) =>
          Replies.reply(
            tgGateway,
            chatId,
            s"Unknown strategy: ${id.value}. Try /strategies for the list."
          )
        case BacktestServiceError.Failed(BacktestError.NotEnoughBars(required, actual)) =>
          Replies.reply(
            tgGateway,
            chatId,
            s"Not enough price history for $tickerLabel: need $required bars, have $actual. " +
              s"Try /watchlist add $tickerLabel to start collecting data."
          )
      }

  // Sent after the text reply, never instead of it: a rendering or sending failure is logged and
  // swallowed here so it can never turn an otherwise-successful /backtest into a failed command.
  private def sendEquityCurveImage(chatId: tg.ChatId, run: BacktestRun): F[Unit] = {
    val points = run.result.equityCurve
    if (points.size < 2) F.unit
    else {
      val filename = s"backtest-${run.id.value}-equity.png"
      val attempt = F.catchNonFatal(EquityCurveImage.render(points)).flatMap { bytes =>
        tgGateway.sendPhoto(chatId, filename, bytes, None)
      }
      attempt.recoverWith { case e =>
        warn"Failed to render or send the equity-curve image for backtest #${run.id.value}: ${e.getMessage}"
      }
    }
  }

  // "params=fast:50,slow:200" -> Map("fast" -> "50", "slow" -> "200"). Any malformed pair (missing
  // ":", or an empty key/value) fails the whole token; the handler only distinguishes success from
  // failure (replying with the one-line usage string on any Left), so this message is informational
  // rather than user-facing.
  private def parseParams(token: String): Either[String, Map[String, String]] = {
    val body = token.drop(ParamsPrefix.length)
    if (body.isEmpty) Left("empty params")
    else
      body.split(",").toList.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) {
        (acc, pair) =>
          acc.flatMap { m =>
            pair.split(":", 2).toList match {
              case k :: v :: Nil if k.nonEmpty && v.nonEmpty => Right(m.updated(k, v))
              case _ => Left(s"invalid params pair: $pair")
            }
          }
      }
  }

  private def formatSuccess(run: BacktestRun): String = {
    val r = run.result
    val m = r.metrics
    List(
      Some(s"Backtest #${run.id.value}: ${r.strategyId.value} on ${r.ticker.value}"),
      Some(s"Period: ${r.dateFrom} to ${r.dateTo}"),
      Option.when(r.equityCurve.nonEmpty)(s"Bars: ${r.equityCurve.size}"),
      Option.when(r.parameters.nonEmpty)(s"Parameters: ${formatParams(r.parameters)}"),
      Some(s"Trades: ${m.tradeCount}"),
      Some(s"Total return: ${Replies.pct(m.totalReturn)}"),
      Some(s"Annualized: ${Replies.pct(m.annualizedReturn)}"),
      Some(s"Max drawdown: ${Replies.pct(m.maxDrawdown)}"),
      Some(s"Sharpe: ${Replies.scaled(m.sharpeAnnualized)}"),
      Some(s"Win rate: ${m.winRate.fold("n/a")(Replies.pct)}"),
      Some(s"Buy and hold: ${Replies.pct(m.buyAndHoldReturn)}")
    ).flatten.mkString("\n")
  }

  private def formatParams(params: Map[String, String]): String =
    params.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(", ")
}
