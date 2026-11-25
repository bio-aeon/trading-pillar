package st.btu.trading.pillar.gateways.tcs

import cats.Functor
import cats.effect.{Async, Resource, Sync, Temporal}
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import com.google.protobuf.timestamp.Timestamp
import ru.tinkoff.piapi.contract.v1.common.Quotation
import ru.tinkoff.piapi.contract.v1.instruments.*
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.facades.TcsGrpc
import st.btu.trading.pillar.gateways.InstrumentDirectory
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.tcs
import st.btu.trading.pillar.storages.BrokerInstrumentStorage
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{Instant, LocalDate, ZoneId, ZonedDateTime}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

/** An [[InstrumentDirectory]] over Tinkoff Invest, whose instrument ids are FIGIs. A key already in
  * the storage is answered from it; any other is fetched from the broker and, when the broker knows
  * it, stored, so an instrument the broker knows is fetched once. A key of another broker is
  * answered with no instrument.
  *
  * A record of type futures also gets its `FutureTerms`, from the broker's contract lookup, and
  * this directory is the only writer of them. The exchange sets the money a price step is worth at
  * each day's clearing, so a stored future's record is asked again once a day old, until its
  * contract has expired in the exchange zone. A record stored without terms is asked on the same
  * rule, so it gains them once the broker gives them. An answer with terms replaces the stored
  * ones; an answer without terms keeps them. Either answer renews the record's `updatedAt`. A
  * failed or timed out refresh is logged, keeps the stored record and does not renew `updatedAt`,
  * so the next lookup tries again; the lookup answers with the stored record either way. Stored
  * records of any other type are never refreshed.
  */
final class TcsInstrumentDirectory[F[_]] private (
  instruments: InstrumentsServiceFs2Grpc[F, Unit],
  storage: BrokerInstrumentStorage[F],
  zone: ZoneId
)(implicit F: Temporal[F], log: Logging[F])
    extends InstrumentDirectory[F] {
  import TcsInstrumentDirectory.*

  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]] =
    key.broker match {
      case Broker.Tinkoff => lookUp(key)
      case Broker.Ibkr | Broker.Binance => F.pure(None)
    }

  private def lookUp(key: InstrumentKey): F[Option[BrokerInstrument]] =
    storage.find(key).flatMap {
      case Some(stored) => refreshed(stored).map(_.some)
      case None => fetchAndStore(key)
    }

  private def fetchAndStore(key: InstrumentKey): F[Option[BrokerInstrument]] =
    fetch(key).flatMap {
      case Some(instrument) =>
        for {
          terms <- termsFor(instrument)
          known <- toBrokerInstrument(instrument, terms)
          _ <- storage.upsert(known)
        } yield known.some
      case None => F.pure(None)
    }

  // An instrument the broker does not know is an answer (None), not a failure.
  private def fetch(key: InstrumentKey): F[Option[Instrument]] = {
    val request =
      InstrumentRequest(
        InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI,
        classCode = None,
        id = key.id.value
      )
    F.adaptError(
      instruments
        .getInstrumentBy(request, ())
        .map(_.instrument)
        .recover { case e if TcsErrors.isNotFound(e) => None }
    )(TcsErrors.toMarketDataError(None))
  }

  // A contract the broker does not know, or describes without usable terms, has none.
  private def fetchTerms(figi: String): F[Option[FutureTerms]] = {
    val request =
      InstrumentRequest(InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI, classCode = None, figi)
    F.adaptError(
      instruments
        .futureBy(request, ())
        .map(_.instrument.flatMap(termsFrom(_, zone)))
        .recover { case e if TcsErrors.isNotFound(e) => None }
    )(TcsErrors.toMarketDataError(None))
  }

  private def termsFor(instrument: Instrument): F[Option[FutureTerms]] =
    if (instrument.instrumentType == tcs.FuturesInstrumentType) fetchTerms(instrument.figi)
    else F.pure(None)

  private def toBrokerInstrument(
    instrument: Instrument,
    terms: Option[FutureTerms]
  ): F[BrokerInstrument] =
    UtcClock.nowUtc[F].map { now =>
      BrokerInstrument(
        InstrumentKey(Broker.Tinkoff, BrokerInstrumentId(instrument.figi)),
        Ticker(instrument.ticker),
        Option(instrument.classCode).filter(_.nonEmpty).map(ClassCode(_)),
        instrument.name,
        instrument.instrumentType,
        Currency(instrument.currency),
        now,
        terms
      )
    }

  private def refreshed(stored: BrokerInstrument): F[BrokerInstrument] =
    if (!stored.isFuture) F.pure(stored)
    else
      UtcClock.nowUtc[F].flatMap { now =>
        if (isDue(stored, now)) refresh(stored, now) else F.pure(stored)
      }

  private def isDue(stored: BrokerInstrument, now: ZonedDateTime): Boolean = {
    val askedAgainAt = stored.updatedAt.toInstant.plus(FutureTermsMaxAge.toJava)
    !now.toInstant.isBefore(askedAgainAt) && !stored.expiredOn(
      now.withZoneSameInstant(zone).toLocalDate
    )
  }

  private def refresh(stored: BrokerInstrument, now: ZonedDateTime): F[BrokerInstrument] =
    F.timeout(fetchTerms(stored.instrument.id.value), FutureTermsRefreshTimeout).attempt.flatMap {
      case Right(Some(terms)) =>
        renew(stored.copy(futureTerms = Some(terms), updatedAt = now))
      case Right(None) =>
        warn"The broker gave no usable terms for the future ${stored.ticker}; the stored record stays" >>
          renew(stored.copy(updatedAt = now))
      case Left(error) =>
        warnCause"Refreshing the terms of the future ${stored.ticker} failed; the stored record stays" (
          error
        ).as(stored)
    }

  private def renew(record: BrokerInstrument): F[BrokerInstrument] =
    storage.upsert(record).as(record)
}

object TcsInstrumentDirectory {

  // The exchange sets the money a price step is worth at each day's clearing.
  private[tcs] val FutureTermsMaxAge: FiniteDuration = 1.day

  private[tcs] val FutureTermsRefreshTimeout: FiniteDuration = 10.seconds

  // Tinkoff's dates are timestamps at the midnight of the exchange day in UTC or in Moscow; read in
  // the exchange zone, both give that day.
  private def exchangeDay(timestamp: Timestamp, zone: ZoneId): LocalDate =
    Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong).atZone(zone).toLocalDate

  private def positive(quotation: Option[Quotation]): Option[BigDecimal] =
    quotation
      .map(q => Prices.fromUnitsNano(q.units, q.nano))
      .filter(_.signum > 0)

  // None unless the contract has an underlying, both dates and a positive price step with a
  // positive value: without them a point has no value.
  private def termsFrom(future: Future, zone: ZoneId): Option[FutureTerms] =
    for {
      lastTradeDate <- future.lastTradeDate.map(exchangeDay(_, zone))
      expirationDate <- future.expirationDate.map(exchangeDay(_, zone))
      step <- positive(future.minPriceIncrement)
      stepValue <- positive(future.minPriceIncrementAmount)
      if future.basicAsset.nonEmpty
    } yield FutureTerms(
      Underlying(future.basicAsset),
      lastTradeDate,
      expirationDate,
      step,
      stepValue
    )

  /** Wires an [[InstrumentDirectory]] over the given Tinkoff gRPC stub, remembering what it fetches
    * in `storage`. `zone` is the exchange's time zone, in which a contract's dates are days.
    */
  def create[I[_]: Functor, F[_]: Temporal](
    instruments: InstrumentsServiceFs2Grpc[F, Unit],
    storage: BrokerInstrumentStorage[F],
    zone: ZoneId
  )(implicit logs: Logs[I, F]): I[TcsInstrumentDirectory[F]] =
    logs
      .forService[TcsInstrumentDirectory[F]]
      .map(implicit log => new TcsInstrumentDirectory[F](instruments, storage, zone))

  /** Wires an [[InstrumentDirectory]] authenticated as `token`, holding its gRPC channel and stub
    * open for the resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async](
    token: tcs.Token,
    storage: BrokerInstrumentStorage[F],
    zone: ZoneId
  )(implicit logs: Logs[I, F], liftFI: Lift[F, I]): Resource[I, TcsInstrumentDirectory[F]] = {
    val metadata = TcsGrpc.bearerMetadata(token)

    for {
      channel <- TcsGrpc.channel[I]
      instrumentsStub <- InstrumentsServiceFs2Grpc
        .clientResource[F, Unit](channel, (_: Unit) => metadata)
        .mapK(liftFI.liftF)
      directory <- Resource.eval(create[I, F](instrumentsStub, storage, zone))
    } yield directory
  }
}
