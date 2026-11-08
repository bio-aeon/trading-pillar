package st.btu.trading.pillar.gateways.tcs

import cats.MonadThrow
import cats.effect.{Async, Clock, Resource, Sync}
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import ru.tinkoff.piapi.contract.v1.instruments.{
  Instrument,
  InstrumentIdType,
  InstrumentRequest,
  InstrumentsServiceFs2Grpc
}
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.facades.TcsGrpc
import st.btu.trading.pillar.gateways.InstrumentDirectory
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, Ticker}
import st.btu.trading.pillar.models.tcs
import st.btu.trading.pillar.storages.BrokerInstrumentStorage
import tofu.lift.Lift

/** An [[InstrumentDirectory]] over Tinkoff Invest, whose instrument ids are FIGIs. A key already in
  * the storage is answered from it; any other is fetched from the broker and, when the broker knows
  * it, stored, so an instrument the broker knows is fetched once. A stored record is never
  * refreshed.
  */
final class TcsInstrumentDirectory[F[_]] private (
  instruments: InstrumentsServiceFs2Grpc[F, Unit],
  storage: BrokerInstrumentStorage[F]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends InstrumentDirectory[F] {

  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]] =
    key.broker match {
      case Broker.Tinkoff => lookUp(key)
    }

  private def lookUp(key: InstrumentKey): F[Option[BrokerInstrument]] =
    storage.find(key).flatMap {
      case stored @ Some(_) => F.pure(stored)
      case None => fetchAndStore(key)
    }

  private def fetchAndStore(key: InstrumentKey): F[Option[BrokerInstrument]] =
    fetch(key).flatMap {
      case Some(instrument) =>
        toBrokerInstrument(instrument).flatMap(known => storage.upsert(known).as(known.some))
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

  private def toBrokerInstrument(instrument: Instrument): F[BrokerInstrument] =
    UtcClock.nowUtc[F].map { now =>
      BrokerInstrument(
        InstrumentKey(Broker.Tinkoff, BrokerInstrumentId(instrument.figi)),
        Ticker(instrument.ticker),
        Option(instrument.classCode).filter(_.nonEmpty).map(ClassCode(_)),
        instrument.name,
        instrument.instrumentType,
        Currency(instrument.currency),
        now
      )
    }
}

object TcsInstrumentDirectory {

  /** Wires an [[InstrumentDirectory]] over the given Tinkoff gRPC stub, remembering what it fetches
    * in `storage`.
    */
  def create[F[_]: MonadThrow: Clock](
    instruments: InstrumentsServiceFs2Grpc[F, Unit],
    storage: BrokerInstrumentStorage[F]
  ): TcsInstrumentDirectory[F] =
    new TcsInstrumentDirectory[F](instruments, storage)

  /** Wires an [[InstrumentDirectory]] authenticated as `token`, holding its gRPC channel and stub
    * open for the resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async](token: tcs.Token, storage: BrokerInstrumentStorage[F])(
    implicit liftFI: Lift[F, I]
  ): Resource[I, TcsInstrumentDirectory[F]] = {
    val metadata = TcsGrpc.bearerMetadata(token)

    for {
      channel <- TcsGrpc.channel[I]
      instrumentsStub <- InstrumentsServiceFs2Grpc
        .clientResource[F, Unit](channel, (_: Unit) => metadata)
        .mapK(liftFI.liftF)
    } yield create[F](instrumentsStub, storage)
  }
}
