package st.btu.trading.pillar.tax

import cats.syntax.functor.*
import st.btu.trading.pillar.models.domain.*

import java.time.LocalDate

/** Builds one year's [[AnnualTaxReport]] from a FIFO ledger plus the operations it was built from.
  * `operations` supplies what the ledger alone cannot: the dividend, coupon and withheld-tax
  * amounts, which never open or close a lot. This is an estimate for personal reference, not tax
  * advice: it does not know about loss carry-forward, IIS deductions, or any rule beyond the ones
  * implemented here.
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

    val (realizedGains, exemptTotal, gainsUnconverted) =
      yearGains.foldLeft((Zero, Zero, 0)) { case ((realized, exempt, unconverted), rg) =>
        convert(rg.gain, rg.currency, rg.sellDate) match {
          case Some(converted) =>
            // An FX rate is always positive, so the sign of `converted` always matches the sign
            // of `rg.gain` - checking either is equivalent.
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
    val coupons = sumConverted(yearOps.filter(_.kind == OperationKind.Coupon), convert, _.amount)
    val withheld =
      sumConverted(yearOps.filter(_.kind == OperationKind.Tax), convert, op => -op.amount)

    val estimatedTax = progressiveTax(taxableGains, config)
    val unconverted =
      gainsUnconverted + dividends.unconverted + coupons.unconverted + withheld.unconverted

    AnnualTaxReport(
      year,
      realizedGains,
      exemptGains,
      taxableGains,
      dividends.total,
      coupons.total,
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
