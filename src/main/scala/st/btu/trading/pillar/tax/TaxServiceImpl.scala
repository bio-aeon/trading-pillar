package st.btu.trading.pillar.tax

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.analytics.{FxRates, OperationHistoryYears}
import st.btu.trading.pillar.gateways.{InstrumentDirectory, MarketDataSources}
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi, TcsPortfolioOperation}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.storages.{PriceHistoryStorage, TcsPortfolioOperationStorage}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

// Same "full history, then narrow" shape as PortfolioValuationServiceImpl: operations are always
// loaded over an OperationHistoryYears window ending now, regardless of the report year requested, so a lot
// opened long before the report's own year is still on hand to be matched against a sale inside
// it.
final class TaxServiceImpl[F[_]: Monad: Clock] private (
  operations: TcsPortfolioOperationStorage[F],
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

  // Resolves the ticker back to its figis through the same tickerByFigi mapping buildLedger already
  // computed, because the directory only looks up by figi. A ticker can have a figi per board, so
  // the lots of every one are returned: concatenated in figi order, then stably sorted by date,
  // which leaves lots acquired on the same date in figi order.
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]] =
    for {
      ops <- loadOperations(accountId)
      (ledger, tickerByFigi) <- buildLedger(ops)
    } yield tickerByFigi.toList
      .collect { case (figi, t) if t == ticker => figi }
      .sortBy(_.value)
      .flatMap(figi => ledger.open.getOrElse(figi, Nil))
      .sortBy(_.acquiredOn.toEpochDay)

  private def loadOperations(accountId: AccountId): F[List[TcsPortfolioOperation]] =
    Clock[F].realTimeInstant.flatMap { instant =>
      val now = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
      operations
        .operationsByDtRange(accountId, now.minusYears(OperationHistoryYears.toLong), now)
        .map(_.toList)
    }

  // tickerByFigi is resolved once per call, over the distinct figis actually present in `ops` - at
  // most one InstrumentDirectory lookup per figi, not per operation. Returned alongside the ledger
  // so openLots can resolve a ticker back to its figis through the very same mapping.
  private def buildLedger(ops: List[TcsPortfolioOperation]): F[(LotLedger, Map[Figi, Ticker])] =
    ops.iterator
      .flatMap(_.figi)
      .toSet
      .toVector
      .traverse(figi => directory.byFigi(figi).map(found => figi -> found.map(_.ticker)))
      .map { pairs =>
        val tickerByFigi = pairs.collect { case (figi, Some(ticker)) => figi -> ticker }.toMap
        (TaxLots.fifo(ops, tickerByFigi.get), tickerByFigi)
      }

  // One PriceHistoryStorage.bars call per non-base currency actually used by `ops`, on the source
  // its rate ticker is configured to use and covering the same OperationHistoryYears window
  // loadOperations reads from - then a plain, F-free lookup function TaxReport.annual can call as
  // many times as it needs.
  private def fxRateFn(
    ops: List[TcsPortfolioOperation]
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

  /** Wires a [[TaxService]] that maps FIGIs through `directory` and reads exchange rates from
    * `priceHistory` as `sources` configures them.
    */
  def create[F[_]: Monad: Clock](
    operations: TcsPortfolioOperationStorage[F],
    directory: InstrumentDirectory[F],
    sources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    config: TaxConfig
  ): TaxService[F] =
    new TaxServiceImpl[F](operations, directory, sources, priceHistory, config)
}
