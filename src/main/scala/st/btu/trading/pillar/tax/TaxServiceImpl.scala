package st.btu.trading.pillar.tax

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.analytics.{FxRates, OperationHistoryYears}
import st.btu.trading.pillar.gateways.{InstrumentDirectory, MarketDataSources}
import st.btu.trading.pillar.models.domain.{AccountId, AccountOperation, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.storages.{AccountOperationStorage, PriceHistoryStorage}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

// Same "full history, then narrow" shape as PortfolioValuationServiceImpl: operations are always
// loaded over an OperationHistoryYears window ending now, regardless of the report year requested, so a lot
// opened long before the report's own year is still on hand to be matched against a sale inside
// it.
final class TaxServiceImpl[F[_]: Monad: Clock] private (
  operations: AccountOperationStorage[F],
  directory: InstrumentDirectory[F],
  sources: MarketDataSources[F],
  priceHistory: PriceHistoryStorage[F],
  val config: TaxConfig
) extends TaxService[F] {

  def annualReport(accountId: AccountId, year: Int): F[AnnualTaxReport] =
    for {
      ops <- loadOperations(accountId)
      (ledger, _) <- buildLedger(ops)
      fxRate <- fxRateFn(ops)
    } yield TaxReport.annual(year, ledger, ops, fxRate, config)

  // Resolves the ticker back to its instruments through the same tickerByInstrument mapping
  // buildLedger already computed, because the directory only looks up by key. A ticker can have an
  // instrument per board, so the lots of every one are returned: concatenated in instrument order,
  // then stably sorted by date, which leaves lots acquired on the same date in instrument order.
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]] =
    for {
      ops <- loadOperations(accountId)
      (ledger, tickerByInstrument) <- buildLedger(ops)
    } yield tickerByInstrument.toList
      .collect { case (instrument, t) if t == ticker => instrument }
      .sorted
      .flatMap(instrument => ledger.open.getOrElse(instrument, Nil))
      .sortBy(_.acquiredOn.toEpochDay)

  private def loadOperations(accountId: AccountId): F[List[AccountOperation]] =
    Clock[F].realTimeInstant.flatMap { now =>
      val since =
        ZonedDateTime
          .ofInstant(now, ZoneOffset.UTC)
          .minusYears(OperationHistoryYears.toLong)
          .toInstant
      operations.operations(accountId, since, now).map(_.toList)
    }

  // tickerByInstrument is resolved once per call, over the distinct instruments actually present
  // in `ops` - at most one InstrumentDirectory lookup per instrument, not per operation. Returned
  // alongside the ledger so openLots can resolve a ticker back to its instruments through the very
  // same mapping.
  private def buildLedger(ops: List[AccountOperation]): F[(LotLedger, Map[InstrumentKey, Ticker])] =
    ops.iterator
      .flatMap(_.instrument)
      .toSet
      .toVector
      .traverse(key => directory.byKey(key).map(found => key -> found.map(_.ticker)))
      .map { pairs =>
        val tickerByInstrument =
          pairs.collect { case (key, Some(ticker)) => key -> ticker }.toMap
        (TaxLots.fifo(ops, tickerByInstrument.get), tickerByInstrument)
      }

  // One PriceHistoryStorage.bars call per non-base currency actually used by `ops`, on the source
  // its rate ticker is configured to use and covering the same OperationHistoryYears window
  // loadOperations reads from - then a plain, F-free lookup function TaxReport.annual can call as
  // many times as it needs.
  private def fxRateFn(
    ops: List[AccountOperation]
  ): F[(Currency, LocalDate) => Option[BigDecimal]] =
    Clock[F].realTimeInstant.flatMap { instant =>
      val today = LocalDate.ofInstant(instant, ZoneOffset.UTC)
      val earliest = today.minusYears(OperationHistoryYears.toLong)
      val currencies = ops.iterator.map(_.currency).toSet - config.baseCurrency
      currencies.toVector
        .traverse { ccy =>
          FxRates
            .loadBars(
              priceHistory,
              sources,
              config.fxTickers,
              ccy,
              config.baseCurrency,
              earliest,
              today
            )
            .map(ccy -> _)
        }
        .map { pairs =>
          val barsByCcy = pairs.toMap
          (ccy: Currency, date: LocalDate) =>
            barsByCcy.get(ccy).flatMap(FxRates.closeOnOrBefore(_, date))
        }
    }
}

object TaxServiceImpl {

  /** Wires a [[TaxService]] over the operations in `operations` that maps instruments through
    * `directory` and reads exchange rates from `priceHistory` as `sources` configures them.
    */
  def create[F[_]: Monad: Clock](
    operations: AccountOperationStorage[F],
    directory: InstrumentDirectory[F],
    sources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    config: TaxConfig
  ): TaxService[F] =
    new TaxServiceImpl[F](operations, directory, sources, priceHistory, config)
}
