package st.btu.trading.pillar.services

import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import cats.{Functor, MonadThrow}
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.gateways.{InstrumentDirectory, MarketDataSources}
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.{AccountId, InstrumentKey}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.services.HeldInstrumentPricing.Outcome
import st.btu.trading.pillar.services.commands.SourceSettings
import st.btu.trading.pillar.storages.{
  AccountOperationStorage,
  AccountStorage,
  TickerSourceStorage,
  WatchlistStorage
}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.ZonedDateTime

final class HeldInstrumentsServiceImpl[F[_]: Logging] private (
  accounts: AccountStorage[F],
  operations: AccountOperationStorage[F],
  watchlist: WatchlistStorage[F],
  tickerSources: TickerSourceStorage[F],
  directory: InstrumentDirectory[F],
  sources: MarketDataSources[F]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends HeldInstrumentsService[F] {
  import HeldInstrumentsServiceImpl.*

  def addToWatchlists: F[Unit] =
    for {
      now <- UtcClock.nowUtc[F]
      stored <- accounts.allAccounts
      _ <- stored.traverse_(addHeldInstruments(_, now))
    } yield ()

  private def addHeldInstruments(account: Account, now: ZonedDateTime): F[Unit] =
    for {
      held <- AccountHoldings.before(operations, account.id, now.toInstant)
      instruments <- held.keys.toList.sorted.traverse(lookUp(account.id, _)).map(_.flatten)
      listed <- watchlist.byAccount(account.id).map(_.map(_.ticker).toSet)
      // Two instruments can share a ticker, such as one listed on two boards: the first stands.
      _ <- instruments.distinctBy(_.ticker).traverse_(watch(account.id, listed, now, _))
    } yield ()

  // A failed lookup is logged and gives no instrument, so that it does not stop the others.
  private def lookUp(accountId: AccountId, key: InstrumentKey): F[Option[BrokerInstrument]] =
    directory.byKey(key).handleErrorWith { error =>
      warnCause"Lookup of instrument ${key.id} held by account ${accountId.value} failed" (error)
        .as(Option.empty[BrokerInstrument])
    }

  // The setting goes first: a ticker that is on the watchlist while its setting is still missing
  // would be synced from the default source.
  private def watch(
    accountId: AccountId,
    listed: Set[Ticker],
    now: ZonedDateTime,
    instrument: BrokerInstrument
  ): F[Unit] =
    ensureSourceSetting(instrument) >> F.unlessA(listed(instrument.ticker))(
      addToWatchlist(accountId, instrument.ticker, now)
    )

  private def ensureSourceSetting(instrument: BrokerInstrument): F[Unit] =
    tickerSources.find(instrument.ticker).flatMap {
      case Some(_) => F.unit
      case None =>
        HeldInstrumentPricing.of(instrument, sources.serves) match {
          case Outcome.Setting(setting) =>
            val described = SourceSettings.describe(setting)
            tickerSources.upsert(TickerSource(instrument.ticker, setting)) >>
              info"Set held ticker ${instrument.ticker} to $described"
          case Outcome.NoSetting => F.unit
          case Outcome.NoExchangeRule(exchange) => noExchangeRule(instrument.ticker, exchange)
        }
    }

  private def noExchangeRule(ticker: Ticker, exchange: Option[ClassCode]): F[Unit] = {
    val listedOn = exchange.fold("no exchange")(_.value)
    warn"No Yahoo symbol rule for $ticker listed on $listedOn; it keeps the default source"
  }

  // `WatchlistStorage.add` can re-enable an entry that exists, so it is only called for a ticker
  // the account lacks.
  private def addToWatchlist(accountId: AccountId, ticker: Ticker, now: ZonedDateTime): F[Unit] =
    watchlist.add(WatchlistItem(accountId, ticker, enabled = true, Some(HeldNote), now)) >>
      info"Added held ticker $ticker to the watchlist of account ${accountId.value}"
}

object HeldInstrumentsServiceImpl {

  private val HeldNote = "Added because the account holds it"

  /** Wires a [[HeldInstrumentsService]] that finds the instruments of every account in `accounts`
    * from their executed operations in `operations`, resolves them through `directory`, and writes
    * to `watchlist` and `tickerSources`. It gives a ticker a source setting only for a source that
    * `sources` serves.
    */
  def create[I[_]: Functor, F[_]: MonadThrow: Clock](
    accounts: AccountStorage[F],
    operations: AccountOperationStorage[F],
    watchlist: WatchlistStorage[F],
    tickerSources: TickerSourceStorage[F],
    directory: InstrumentDirectory[F],
    sources: MarketDataSources[F]
  )(implicit logs: Logs[I, F]): I[HeldInstrumentsService[F]] =
    logs
      .forService[HeldInstrumentsService[F]]
      .map(implicit log =>
        new HeldInstrumentsServiceImpl[F](
          accounts,
          operations,
          watchlist,
          tickerSources,
          directory,
          sources
        )
      )
}
