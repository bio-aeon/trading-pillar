package st.btu.trading.pillar.tax

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.analytics.{FxRates, OperationHistoryYears}
import st.btu.trading.pillar.models.domain.{AccountId, Figi, TcsPortfolioOperation}
import st.btu.trading.pillar.models.market.{Source, Ticker}
import st.btu.trading.pillar.storages.{
  InstrumentStorage,
  PriceHistoryStorage,
  TcsPortfolioOperationStorage
}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

// Same "full history, then narrow" shape as PortfolioValuationServiceImpl: operations are always
// loaded over an OperationHistoryYears window ending now, regardless of the report year requested, so a lot
// opened long before the report's own year is still on hand to be matched against a sale inside
// it.
final class TaxServiceImpl[F[_]: Monad: Clock] private (
  operations: TcsPortfolioOperationStorage[F],
  instruments: InstrumentStorage[F],
  priceHistory: PriceHistoryStorage[F],
  val config: TaxConfig
) extends TaxService[F] {

  import TaxServiceImpl.*

  def annualReport(accountId: AccountId, year: Int): F[AnnualTaxReport] =
    for {
      ops <- loadOperations(accountId)
      (ledger, _) <- buildLedger(ops)
      fxRate <- fxRateFn(ops)
    } yield TaxReport.annual(year, ledger, ops, fxRate, config)

  // Resolves the ticker back to a figi through the same tickerByFigi mapping buildLedger already
  // computed - not a separate instruments.find(ticker) lookup, which would answer from
  // InstrumentInfo.figi alone and come back empty for an instrument priced from a source that
  // carries no Tinkoff cross-reference (e.g. Yahoo).
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]] =
    for {
      ops <- loadOperations(accountId)
      (ledger, tickerByFigi) <- buildLedger(ops)
    } yield tickerByFigi
      .collectFirst { case (figi, t) if t == ticker => figi }
      .flatMap(ledger.open.get)
      .getOrElse(Nil)

  private def loadOperations(accountId: AccountId): F[List[TcsPortfolioOperation]] =
    Clock[F].realTimeInstant.flatMap { instant =>
      val now = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
      operations
        .operationsByDtRange(accountId, now.minusYears(OperationHistoryYears.toLong), now)
        .map(_.toList)
    }

  // tickerByFigi is resolved once per call, over the distinct figis actually present in `ops` - at
  // most one InstrumentStorage.findByFigi lookup per figi, not per operation. Returned alongside
  // the ledger so openLots can resolve a ticker back to its figi through the very same mapping.
  private def buildLedger(ops: List[TcsPortfolioOperation]): F[(LotLedger, Map[Figi, Ticker])] =
    ops.iterator
      .flatMap(_.figi)
      .toSet
      .toVector
      .traverse(figi => instruments.findByFigi(figi.value).map(info => figi -> info.map(_.ticker)))
      .map { pairs =>
        val tickerByFigi = pairs.collect { case (figi, Some(ticker)) => figi -> ticker }.toMap
        (TaxLots.fifo(ops, tickerByFigi.get), tickerByFigi)
      }

  // One PriceHistoryStorage.bars call per non-base currency actually used by `ops`, covering the
  // same OperationHistoryYears window loadOperations reads from - then a plain, F-free lookup
  // function TaxReport.annual can call as many times as it needs.
  private def fxRateFn(
    ops: List[TcsPortfolioOperation]
  ): F[(String, LocalDate) => Option[BigDecimal]] =
    Clock[F].realTimeInstant.flatMap { instant =>
      val today = LocalDate.ofInstant(instant, ZoneOffset.UTC)
      val earliest = today.minusYears(OperationHistoryYears.toLong)
      val currencies = ops.iterator.map(_.currency.value).toSet - config.baseCurrency
      currencies.toVector
        .traverse { ccy =>
          FxRates
            .loadBars(priceHistory, ccy, config.baseCurrency, FxSource, earliest, today)
            .map(ccy -> _)
        }
        .map { pairs =>
          val barsByCcy = pairs.toMap
          (ccy: String, date: LocalDate) =>
            barsByCcy.get(ccy).flatMap(FxRates.closeOnOrBefore(_, date))
        }
    }
}

object TaxServiceImpl {

  private val FxSource: Source = Source.Yahoo

  /** Wires a [[TaxService]] backed by `operations`, `instruments` and `priceHistory`. */
  def create[F[_]: Monad: Clock](
    operations: TcsPortfolioOperationStorage[F],
    instruments: InstrumentStorage[F],
    priceHistory: PriceHistoryStorage[F],
    config: TaxConfig
  ): TaxService[F] = new TaxServiceImpl[F](operations, instruments, priceHistory, config)
}
