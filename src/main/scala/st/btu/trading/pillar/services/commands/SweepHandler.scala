package st.btu.trading.pillar.services.commands

import cats.effect.{Async, Clock}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGateway}
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.PriceHistoryStorage
import st.btu.trading.pillar.strategy.{ParameterizedStrategy, StrategyId, StrategyRegistry}
import st.btu.trading.pillar.strategy.backtest.*

import scala.util.Try

// Single-account app for now, same as BacktestHandler: no account/watchlist write happens here, so
// there's nothing account-specific to thread through. Unlike /backtest, a sweep's points are never
// persisted (no BacktestRunStorage dependency) - the grid is run fresh every time directly against
// stored bars.
final class SweepHandler[F[_]: Clock: Async](
  tgGateway: TgGateway[F],
  registry: StrategyRegistry[F],
  priceHistory: PriceHistoryStorage[F],
  marketDataSources: MarketDataSources[F],
  defaults: BacktestConfig
) extends CommandHandler[F] {

  private val DefaultYears = 2
  private val TopN = 5

  def command: Command = Command.Sweep

  def usage: String =
    "/sweep strategyId SYM [years=2] key=from:to:step [key=from:to:step ...] - parameter sweep, top 5 by Sharpe"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case strategyIdRaw :: symRaw :: rest if rest.nonEmpty =>
        parseArgs(rest) match {
          case Right((years, spec)) if spec.nonEmpty =>
            runSweep(chatId, StrategyId(strategyIdRaw), Ticker(symRaw.toUpperCase), years, spec)
          case _ => Replies.reply(tgGateway, chatId, s"Usage: $usage")
        }
      case _ => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  private def runSweep(
    chatId: tg.ChatId,
    strategyId: StrategyId,
    ticker: Ticker,
    years: Int,
    spec: Map[String, (BigDecimal, BigDecimal, BigDecimal)]
  ): F[Unit] =
    registry.get(strategyId).flatMap {
      case None =>
        Replies.reply(
          tgGateway,
          chatId,
          s"Unknown strategy: ${strategyId.value}. Try /strategies for the list."
        )
      case Some(p: ParameterizedStrategy) =>
        Grid.expand(spec) match {
          case Left(msg) => Replies.reply(tgGateway, chatId, s"Invalid grid: $msg")
          case Right(grid) => runGrid(chatId, p, ticker, years, grid)
        }
      case Some(_) =>
        Replies
          .reply(tgGateway, chatId, s"Strategy ${strategyId.value} does not support parameters.")
    }

  private def runGrid(
    chatId: tg.ChatId,
    base: ParameterizedStrategy,
    ticker: Ticker,
    years: Int,
    grid: List[Map[String, String]]
  ): F[Unit] =
    for {
      today <- Replies.todayUtc[F]
      from = today.minusYears(years.toLong)
      source <- MarketDataSources.sourceOf(marketDataSources, ticker)
      bars <- priceHistory.bars(ticker, source, from, today)
      points <- BacktestSweep.run[F](base, grid, bars, defaults)
      _ <- Replies.reply(tgGateway, chatId, format(grid.size, points))
    } yield ()

  // "years=2" overrides the lookback window; every other "key=value" token is a grid axis
  // "key=from:to:step". Any token that is neither fails the whole command.
  private def parseArgs(
    tokens: List[String]
  ): Either[String, (Int, Map[String, (BigDecimal, BigDecimal, BigDecimal)])] =
    tokens.foldLeft[Either[String, (Int, Map[String, (BigDecimal, BigDecimal, BigDecimal)])]](
      Right((DefaultYears, Map.empty))
    ) { (acc, token) =>
      acc.flatMap { case (years, spec) =>
        token.split("=", 2).toList match {
          case key :: value :: Nil if key.equalsIgnoreCase("years") =>
            Try(value.toInt).toOption.filter(_ > 0) match {
              case Some(y) => Right((y, spec))
              case None => Left(s"invalid years: $value")
            }
          case key :: value :: Nil =>
            parseAxis(value) match {
              case Some(axis) => Right((years, spec.updated(key, axis)))
              case None => Left(s"invalid axis: $token")
            }
          case _ => Left(s"invalid token: $token")
        }
      }
    }

  private def parseAxis(value: String): Option[(BigDecimal, BigDecimal, BigDecimal)] =
    value.split(":").toList match {
      case List(f, t, s) =>
        for {
          from <- Try(BigDecimal(f)).toOption
          to <- Try(BigDecimal(t)).toOption
          step <- Try(BigDecimal(s)).toOption
        } yield (from, to, step)
      case _ => None
    }

  private def format(gridSize: Int, points: List[SweepPoint]): String = {
    val top = BacktestSweep.rank(points, _.sharpeAnnualized).take(TopN).collect {
      case SweepPoint(params, Right(result)) => params -> result
    }
    val header = s"Grid: $gridSize combination(s)"
    val body =
      if (top.isEmpty) "No successful sweep points."
      else
        top.zipWithIndex
          .map { case ((params, result), i) => formatPoint(i + 1, params, result) }
          .mkString("\n")
    s"$header\n$body"
  }

  private def formatPoint(
    rank: Int,
    params: Map[String, String],
    result: BacktestResult
  ): String = {
    val m = result.metrics
    s"$rank. ${formatParams(params)} - return ${Replies.pct(m.totalReturn)}, " +
      s"Sharpe ${Replies.scaled(m.sharpeAnnualized)}, " +
      s"max drawdown ${Replies.pct(m.maxDrawdown)}, trades ${m.tradeCount}"
  }

  private def formatParams(params: Map[String, String]): String =
    params.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(", ")
}
