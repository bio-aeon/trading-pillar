package st.btu.trading.pillar.gateways.binance

import cats.MonadThrow
import cats.effect.{Clock, Ref, Sync}
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import sttp.model.StatusCode
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.gateways.MarketDataSource
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*

import java.time.{Instant, LocalDate, ZonedDateTime}
import scala.concurrent.duration.*
import scala.math.BigDecimal.RoundingMode

/** Market data from Binance's spot market: a ticker is the symbol of a pair, such as `BTCUSDC`, and
  * its prices are quoted in the quote asset of the pair.
  *
  * Daily bars are the UTC days that have ended, and a bar's volume is the volume traded in the base
  * asset, rounded down to whole units. A coin pays no dividends, so there are none. The pairs
  * Binance lists are read when first needed and again when the last reading is an hour old.
  */
final class BinanceMarketDataSource[F[_]] private (
  market: BinanceMarket[F],
  listing: Ref[F, Option[BinanceMarketDataSource.Listing]]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends MarketDataSource[F] {
  import BinanceMarketDataSource.*

  def origin: SourceSetting = SourceSetting.Binance

  def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[Bar]] =
    for {
      now <- UtcClock.nowUtc[F].map(_.toInstant)
      bars <- F.adaptError(market.dailyBars(symbolOf(ticker), since, until))(mapError(ticker))
    } yield bars.filter(_.closeTime.isBefore(now)).map(toBar(ticker, _))

  def latestQuote(ticker: Ticker): F[Option[Quote]] =
    pairOf(ticker).flatMap(_.flatTraverse(pair => quoteOf(ticker, pair)))

  def instrumentInfo(ticker: Ticker): F[Option[InstrumentInfo]] =
    pairOf(ticker).flatMap(_.traverse(pair => UtcClock.nowUtc[F].map(describe(ticker, pair, _))))

  def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[DividendEvent]] =
    F.pure(Nil)

  private def quoteOf(ticker: Ticker, pair: BinancePair): F[Option[Quote]] =
    for {
      price <- F.adaptError(market.lastPrice(pair.symbol))(mapError(ticker))
      now <- UtcClock.nowUtc[F]
    } yield price.map { last =>
      Quote(ticker, Prices.fromDecimal(last), Currency(pair.quoteAsset.value), now, Source.Binance)
    }

  private def pairOf(ticker: Ticker): F[Option[BinancePair]] =
    for {
      now <- UtcClock.nowUtc[F].map(_.toInstant)
      stored <- listing.get
      pairs <- stored.filterNot(_.isStale(now)) match {
        case Some(fresh) => F.pure(fresh.pairs)
        case None => read(ticker, now)
      }
    } yield pairs.get(symbolOf(ticker))

  private def read(ticker: Ticker, now: Instant): F[Map[BinanceSymbol, BinancePair]] =
    F.adaptError(market.exchangeInfo)(mapError(ticker)).flatMap { listed =>
      val pairs = listed.map(pair => pair.symbol -> pair).toMap
      listing.set(Some(Listing(now, pairs))).as(pairs)
    }
}

object BinanceMarketDataSource {

  private val PairsTtl = 1.hour

  private val ExchangeName = "BINANCE"
  private val TimeZone = "UTC"

  /** The instrument type of a coin, in the metadata of its ticker and in its broker record. */
  private[pillar] val CoinType = "crypto"

  private final case class Listing(readAt: Instant, pairs: Map[BinanceSymbol, BinancePair]) {
    def isStale(now: Instant): Boolean = !now.isBefore(readAt.plusNanos(PairsTtl.toNanos))
  }

  private def symbolOf(ticker: Ticker): BinanceSymbol = BinanceSymbol(ticker.value)

  private def describe(ticker: Ticker, pair: BinancePair, now: ZonedDateTime): InstrumentInfo =
    InstrumentInfo(
      ticker,
      SourceSetting.Binance,
      Some(s"${pair.baseAsset.value}/${pair.quoteAsset.value}"),
      CoinType,
      Currency(pair.quoteAsset.value),
      ExchangeName,
      TimeZone,
      None,
      now
    )

  // A coin has no splits or dividends to adjust a close for.
  private def toBar(ticker: Ticker, bar: BinanceDailyBar): Bar = {
    val close = Prices.fromDecimal(bar.close)
    Bar(
      ticker,
      bar.date,
      Prices.fromDecimal(bar.open),
      Prices.fromDecimal(bar.high),
      Prices.fromDecimal(bar.low),
      close,
      close,
      bar.volume.setScale(0, RoundingMode.DOWN).toLongExact,
      Source.Binance
    )
  }

  private def mapError(ticker: Ticker): PartialFunction[Throwable, Throwable] = {
    case BinanceError.Refused(BinanceError.InvalidSymbol, _) => MarketDataError.NotFound(ticker)
    case BinanceError.RetriesExhausted(status, _) if status == StatusCode.TooManyRequests.code =>
      MarketDataError.RateLimited(None)
    case failure: BinanceError.RetriesExhausted =>
      MarketDataError.Transient(failure.getMessage, Some(failure))
    case failure: BinanceError.Unreachable =>
      MarketDataError.Transient(failure.getMessage, Some(failure))
    case other =>
      MarketDataError.Other(Option(other.getMessage).getOrElse(other.toString), Some(other))
  }

  /** Wires a [[MarketDataSource]] over `market`, with no pairs read yet. */
  def create[I[_]: Sync, F[_]: Sync](
    market: BinanceMarket[F]
  )(implicit clock: Clock[F]): I[BinanceMarketDataSource[F]] =
    Ref
      .in[I, F, Option[Listing]](None)
      .map(new BinanceMarketDataSource[F](market, _)(using Sync[F], clock))
}
