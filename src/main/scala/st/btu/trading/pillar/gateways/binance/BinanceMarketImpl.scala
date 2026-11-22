package st.btu.trading.pillar.gateways.binance

import cats.effect.{Async, Sync, Temporal}
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.Json
import sttp.client3.SttpBackend
import st.btu.trading.pillar.models.binance.*
import tofu.logging.Logs

import java.time.{Instant, LocalDate, ZoneOffset}

/** A [[BinanceMarket]] over HTTP, which holds no key and so can send no call that needs one.
  *
  * Calls are paced by request weight: each call keeps the next call of its bucket waiting for the
  * share of the bucket's limit that its weight takes, scaled by the configured budget. Build one
  * [[BinanceIpBuckets]] for the process and share it between this client and the clients of every
  * account, since the limits per IP address are shared by every client calling from it. A 429 and a
  * 5xx are tried again after a wait, as is a call that gets no answer, up to the attempts
  * configured. A 418, the ban of the IP address, fails the call at once.
  */
final class BinanceMarketImpl[F[_]] private[binance] (transport: BinanceTransport[F])(implicit
  F: Temporal[F]
) extends BinanceMarket[F] {
  import BinanceMarketImpl.*

  def exchangeInfo: F[List[BinancePair]] =
    call(PublicEndpoint.ExchangeInfo, Nil).flatMap(BinanceResponses.pairs(_).liftTo[F])

  def minuteClose(symbol: BinanceSymbol, minute: Instant): F[Option[BigDecimal]] = {
    val start = minute.toEpochMilli
    call(
      PublicEndpoint.Klines,
      List(
        Parameter.Symbol -> symbol.value,
        Parameter.Interval -> OneMinuteInterval,
        Parameter.StartTime -> start.toString,
        Parameter.EndTime -> (start + MinuteMillis - 1).toString,
        Parameter.Limit -> "1"
      )
    ).flatMap(BinanceResponses.close(_).liftTo[F])
      .recover { case BinanceError.Refused(BinanceError.InvalidSymbol, _) => None }
  }

  def dailyBars(symbol: BinanceSymbol, from: LocalDate, to: LocalDate): F[List[BinanceDailyBar]] = {
    val end = startOfDay(to.plusDays(1)) - 1

    // Reads the bars from `start` a page at a time, up to a short page. A page that brings no day
    // past where it started ends the reading, so that an answer that ignores the start it was asked
    // from cannot keep it going.
    def readFrom(start: Long, collected: Vector[BinanceDailyBar]): F[List[BinanceDailyBar]] =
      call(
        PublicEndpoint.Klines,
        List(
          Parameter.Symbol -> symbol.value,
          Parameter.Interval -> DailyInterval,
          Parameter.StartTime -> start.toString,
          Parameter.EndTime -> end.toString,
          Parameter.Limit -> KlinesPageSize.toString
        )
      ).flatMap(BinanceResponses.dailyBars(_).liftTo[F]).flatMap { page =>
        val all = collected ++ page
        val next = page.lastOption.map(bar => startOfDay(bar.date.plusDays(1)))
        if (page.size < KlinesPageSize) F.pure(all.toList)
        else
          next
            .filter(day => day > start && day <= end)
            .fold(F.pure(all.toList))(readFrom(_, all))
      }

    if (from.isAfter(to)) F.pure(Nil) else readFrom(startOfDay(from), Vector.empty)
  }

  def lastPrice(symbol: BinanceSymbol): F[Option[BigDecimal]] =
    call(PublicEndpoint.TickerPrice, List(Parameter.Symbol -> symbol.value))
      .flatMap(BinanceResponses.price(_).liftTo[F])
      .map(Option(_))
      .recover { case BinanceError.Refused(BinanceError.InvalidSymbol, _) => None }

  private def call(endpoint: PublicEndpoint, parameters: List[(String, String)]): F[Json] =
    transport.public(endpoint, parameters)
}

object BinanceMarketImpl {

  private val OneMinuteInterval = "1m"
  private val DailyInterval = "1d"
  private val MinuteMillis = 60000L

  // How many bars one answer of `klines` holds at most.
  private val KlinesPageSize = 1000

  private def startOfDay(day: LocalDate): Long =
    day.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli

  /** Wires a [[BinanceMarket]] that reads through `backend`, paced by `config`. The limits per IP
    * address are counted in `ipBuckets`, which this client shares with the clients of every
    * account.
    */
  def create[I[_]: Sync, F[_]: Async](
    config: BinanceApiConfig,
    backend: SttpBackend[F, Any],
    ipBuckets: BinanceIpBuckets[F]
  )(implicit logs: Logs[I, F]): I[BinanceMarket[F]] =
    logs
      .forService[BinanceMarket[F]]
      .map { implicit log =>
        new BinanceMarketImpl[F](new BinanceTransport[F](config, backend, ipBuckets.pacers))
      }
}
