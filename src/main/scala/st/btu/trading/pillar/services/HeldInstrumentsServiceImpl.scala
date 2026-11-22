package st.btu.trading.pillar.services

import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import cats.{Functor, MonadThrow}
import st.btu.trading.pillar.analytics.FxRates
import st.btu.trading.pillar.analytics.portfolio.Holdings
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.gateways.{InstrumentDirectory, MarketDataSources}
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.*
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
  sources: MarketDataSources[F],
  rates: RateTickerConfig
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends HeldInstrumentsService[F] {
  import HeldInstrumentsServiceImpl.*

  def addToWatchlists: F[Unit] =
    for {
      now <- UtcClock.nowUtc[F]
      stored <- accounts.allAccounts
      _ <- stored.traverse_(addForAccount(_, now))
    } yield ()

  private def addForAccount(account: Account, now: ZonedDateTime): F[Unit] =
    for {
      history <- AccountHoldings.operationsBefore(operations, account.id, now.toInstant)
      instruments <- Holdings
        .of(history)
        .keys
        .toList
        .sorted
        .traverse(lookUp(account.id, _))
        .map(_.flatten)
      listed <- watchlist.byAccount(account.id).map(_.map(_.ticker).toSet)
      // Two instruments can share a ticker, such as one listed on two boards: the first stands.
      _ <- instruments.distinctBy(_.ticker).traverse_(watch(account.id, listed, now, _))
      _ <- addRateTickers(account.id, currenciesOf(history, instruments), now)
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
  ): F[Unit] = {
    val ticker = instrument.ticker
    val added = F.unlessA(listed(ticker))(addToWatchlist(accountId, ticker, now))
    tickerSources.find(ticker).flatMap {
      case Some(_) => added
      case None =>
        HeldInstrumentPricing.of(instrument, sources.serves) match {
          case Outcome.Setting(setting) =>
            val described = SourceSettings.describe(setting)
            tickerSources.upsert(TickerSource(ticker, setting)) >>
              info"Set held ticker $ticker to $described" >> added
          case Outcome.NoSetting => added
          case Outcome.NoExchangeRule(exchange) => noExchangeRule(ticker, exchange) >> added
          case Outcome.Unwatched =>
            info"No source serves the instruments of ${instrument.instrument.broker.value}; held ticker $ticker is left off the watchlist"
        }
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

  private def currenciesOf(
    history: Iterable[AccountOperation],
    instruments: List[BrokerInstrument]
  ): Set[Currency] =
    history.filter(_.state == OperationState.Executed).map(_.currency).toSet ++
      instruments.map(_.currency)

  // The watchlist is read again, since the held instruments have just been added to it.
  private def addRateTickers(
    accountId: AccountId,
    currencies: Set[Currency],
    now: ZonedDateTime
  ): F[Unit] = {
    val wanted = rateTickersOf(currencies)
    F.whenA(wanted.nonEmpty)(
      watchlist.byAccount(accountId).map(_.map(_.ticker).toSet).flatMap { listed =>
        wanted.filterNot(rate => listed(rate.ticker)).traverse_(addRateTicker(accountId, _, now))
      }
    )
  }

  // The rate ticker of each currency into each base currency it is not, in the order of the tickers.
  // A ticker that several currencies share is added once, for the first of them.
  private def rateTickersOf(currencies: Set[Currency]): List[RateTicker] =
    (for {
      base <- rates.baseCurrencies.toList
      currency <- currencies.toList.filter(_ != base)
    } yield RateTicker(FxRates.rateTicker(currency, base, rates.fxTickers), currency))
      .sortBy(rate => (rate.ticker.value, rate.currency.value))
      .distinctBy(_.ticker)

  private def addRateTicker(accountId: AccountId, rate: RateTicker, now: ZonedDateTime): F[Unit] = {
    val note = s"$RateNotePrefix${rate.currency.value}"
    watchlist.add(WatchlistItem(accountId, rate.ticker, enabled = true, Some(note), now)) >>
      info"Added rate ticker ${rate.ticker} of ${rate.currency.value} to the watchlist of account ${accountId.value}"
  }
}

object HeldInstrumentsServiceImpl {

  private val HeldNote = "Added because the account holds it"
  private val RateNotePrefix = "Added for the exchange rate of "

  // The ticker that carries the rate of a currency.
  private final case class RateTicker(ticker: Ticker, currency: Currency)

  /** Wires a [[HeldInstrumentsService]] that finds the instruments of every account in `accounts`
    * from their executed operations in `operations`, resolves them through `directory`, and writes
    * to `watchlist` and `tickerSources`. It gives a ticker a source setting only for a source that
    * `sources` serves. It also puts on each watchlist the rate tickers that `rates` describes the
    * account's currencies need.
    */
  def create[I[_]: Functor, F[_]: MonadThrow: Clock](
    accounts: AccountStorage[F],
    operations: AccountOperationStorage[F],
    watchlist: WatchlistStorage[F],
    tickerSources: TickerSourceStorage[F],
    directory: InstrumentDirectory[F],
    sources: MarketDataSources[F],
    rates: RateTickerConfig
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
          sources,
          rates
        )
      )
}
