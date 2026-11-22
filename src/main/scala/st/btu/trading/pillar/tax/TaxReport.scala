package st.btu.trading.pillar.tax

import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.models.domain.*

import java.time.LocalDate

/** Builds one year's [[AnnualTaxReport]] from a FIFO ledger plus the operations it was built from.
  * `operations` supplies what the ledger alone cannot: the dividend, coupon and withheld-tax
  * amounts, which never open or close a lot, the cash received as rewards, and the market value of
  * the shares received as stock dividends and of the units received as rewards, which is income as
  * well as a new lot. This is an estimate for personal reference, not tax advice: it does not know
  * about loss carry-forward, IIS deductions, or any rule beyond the ones implemented here.
  */
object TaxReport {

  private val Zero = BigDecimal(0)

  /** Days in each year of `TaxConfig.longTermYears`. */
  val DaysPerYear = 365L

  /** Whether a lot held `holdingDays` counts as long-term under `config`; never under a regime
    * without a long-term exemption.
    */
  def isLongTerm(holdingDays: Long, config: TaxConfig): Boolean =
    config.longTermYears.exists(years => holdingDays >= years * DaysPerYear)

  private final case class Summed(total: BigDecimal, unconverted: Int)

  /** Builds `year`'s tax report from `ledger` and the same `operations` it was computed from,
    * converting every monetary figure to `config.baseCurrency`.
    *
    * A realized gain is converted on the day of its sale: its proceeds less the cost in the
    * currency of the sale are converted together in that currency, and the cost in every other
    * currency is converted in its own and taken off. A gain with an amount that cannot be converted
    * is left out and counted once in `unconverted`.
    *
    * @param fxRate
    *   approximates a currency's conversion rate on or before a date, e.g. from the latest stored
    *   FX close
    */
  def annual(
    year: Int,
    ledger: LotLedger,
    operations: List[AccountOperation],
    fxRate: (Currency, LocalDate) => Option[BigDecimal],
    config: TaxConfig = TaxConfig()
  ): AnnualTaxReport = {
    val base = config.baseCurrency

    def convert(amount: BigDecimal, currency: Currency, date: LocalDate): Option[BigDecimal] =
      if (currency == base) Some(amount) else fxRate(currency, date).map(amount * _)

    val yearGains = ledger.realized.filter(_.sellDate.getYear == year)

    // The cost in the currency of the sale is taken off the proceeds before they are converted, so
    // that a sale whose lots were all paid in its own currency converts exactly one amount.
    def convertedGain(rg: RealizedGain): Option[BigDecimal] =
      for {
        sale <- convert(
          rg.proceeds - rg.costBasis.getOrElse(rg.currency, Zero),
          rg.currency,
          rg.sellDate
        )
        costs <- rg.costBasis.removed(rg.currency).toList.traverse { case (currency, cost) =>
          convert(cost, currency, rg.sellDate)
        }
      } yield sale - costs.sum

    val (realizedGains, exemptTotal, gainsUnconverted) =
      yearGains.foldLeft((Zero, Zero, 0)) { case ((realized, exempt, unconverted), rg) =>
        convertedGain(rg) match {
          case Some(converted) =>
            val exemptPart =
              if (isLongTerm(rg.holdingDays, config) && converted > Zero) converted else Zero
            (realized + converted, exempt + exemptPart, unconverted)
          case None => (realized, exempt, unconverted + 1)
        }
      }
    val exemptGains = config.longTermYears.as(exemptTotal)
    val taxableGains = (realizedGains - exemptTotal).max(Zero)

    val yearOps =
      operations.filter(op => op.state == OperationState.Executed && op.occurredOn.getYear == year)
    val dividends =
      sumConverted(yearOps.filter(_.kind == OperationKind.Dividend), convert, _.amount)
    val stockDividends = sumConverted(
      yearOps.filter(op => op.kind == OperationKind.StockDividend && op.price.isDefined),
      convert,
      op => op.price.fold(Zero)(_ * op.quantity)
    )
    val coupons = sumConverted(yearOps.filter(_.kind == OperationKind.Coupon), convert, _.amount)
    val unitRewards = sumConverted(
      yearOps.filter(op =>
        op.kind == OperationKind.Reward && op.instrument.isDefined && op.price.isDefined
      ),
      convert,
      op => op.price.fold(Zero)(_ * op.quantity)
    )
    val cashRewards = sumConverted(
      yearOps.filter(op => op.kind == OperationKind.Reward && op.instrument.isEmpty),
      convert,
      _.amount
    )
    val withheld =
      sumConverted(yearOps.filter(_.kind == OperationKind.Tax), convert, op => -op.amount)

    val estimatedTax = progressiveTax(taxableGains, config)
    val unconverted =
      gainsUnconverted + dividends.unconverted + stockDividends.unconverted +
        coupons.unconverted + unitRewards.unconverted + cashRewards.unconverted +
        withheld.unconverted

    AnnualTaxReport(
      year,
      realizedGains,
      exemptGains,
      taxableGains,
      dividends.total + stockDividends.total,
      coupons.total,
      unitRewards.total + cashRewards.total,
      withheld.total,
      estimatedTax,
      unconverted,
      base
    )
  }

  private def sumConverted(
    ops: List[AccountOperation],
    convert: (BigDecimal, Currency, LocalDate) => Option[BigDecimal],
    amountOf: AccountOperation => BigDecimal
  ): Summed =
    ops.foldLeft(Summed(Zero, 0)) { (acc, op) =>
      convert(amountOf(op), op.currency, op.occurredOn) match {
        case Some(v) => acc.copy(total = acc.total + v)
        case None => acc.copy(unconverted = acc.unconverted + 1)
      }
    }

  private def progressiveTax(taxableGains: BigDecimal, config: TaxConfig): BigDecimal =
    if (taxableGains <= config.progressiveThreshold) taxableGains * config.lowerRate
    else
      config.progressiveThreshold * config.lowerRate +
        (taxableGains - config.progressiveThreshold) * config.upperRate
}
