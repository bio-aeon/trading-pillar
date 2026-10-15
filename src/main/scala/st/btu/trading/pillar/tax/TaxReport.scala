package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.*

import java.time.{LocalDate, ZoneOffset}

/** Builds one year's [[AnnualTaxReport]] from a FIFO ledger plus the operations it was built from.
  * `operations` supplies what the ledger alone cannot: each figi's trading currency (resolved from
  * any operation that references it), and dividend/coupon/withheld-tax amounts, which never open or
  * close a lot. This is an estimate for personal reference, not tax advice: it does not know about
  * loss carry-forward, IIS deductions, or any rule beyond the ones implemented here.
  */
object TaxReport {

  private val Zero = BigDecimal(0)
  // Shared with services.commands.LotsHandler's own long-term holding-period check.
  val DaysPerYear = 365L

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
    operations: List[TcsPortfolioOperation],
    fxRate: (Currency, LocalDate) => Option[BigDecimal],
    config: TaxConfig = TaxConfig()
  ): AnnualTaxReport = {
    val base = config.baseCurrency

    def convert(amount: BigDecimal, currency: Currency, date: LocalDate): Option[BigDecimal] =
      if (currency == base) Some(amount) else fxRate(currency, date).map(amount * _)

    val figiCurrency: Map[Figi, Currency] =
      operations.iterator.flatMap(op => op.figi.map(_ -> op.currency)).toMap

    val longTermDays = config.longTermYears.toLong * DaysPerYear
    val yearGains = ledger.realized.filter(_.sellDate.getYear == year)

    val (realizedGains, exemptGains, gainsUnconverted) =
      yearGains.foldLeft((Zero, Zero, 0)) { case ((realized, exempt, unconverted), rg) =>
        figiCurrency.get(rg.figi).flatMap(ccy => convert(rg.gain, ccy, rg.sellDate)) match {
          case Some(converted) =>
            // An FX rate is always positive, so the sign of `converted` always matches the sign
            // of `rg.gain` - checking either is equivalent.
            val exemptPart =
              if (rg.holdingDays >= longTermDays && converted > Zero) converted else Zero
            (realized + converted, exempt + exemptPart, unconverted)
          case None => (realized, exempt, unconverted + 1)
        }
      }
    val taxableGains = (realizedGains - exemptGains).max(Zero)

    val yearOps =
      operations.filter(op => op.statusEnum == OperationStatus.Executed && yearOf(op) == year)
    val dividends =
      sumConverted(yearOps.filter(_.operationTypeEnum == OperationType.Dividend), convert, _.amount)
    val coupons =
      sumConverted(yearOps.filter(_.operationTypeEnum == OperationType.Coupon), convert, _.amount)
    val withheld =
      sumConverted(yearOps.filter(_.operationTypeEnum.isWithheldTax), convert, _.amount.abs)

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

  private def yearOf(op: TcsPortfolioOperation): Int =
    op.createdAt.withZoneSameInstant(ZoneOffset.UTC).toLocalDate.getYear

  private def sumConverted(
    ops: List[TcsPortfolioOperation],
    convert: (BigDecimal, Currency, LocalDate) => Option[BigDecimal],
    amountOf: TcsPortfolioOperation => BigDecimal
  ): Summed =
    ops.foldLeft(Summed(Zero, 0)) { (acc, op) =>
      val amount = amountOf(op)
      val date = op.createdAt.withZoneSameInstant(ZoneOffset.UTC).toLocalDate
      convert(amount, op.currency, date) match {
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
