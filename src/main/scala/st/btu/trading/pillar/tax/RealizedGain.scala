package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.{Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.Ticker

import java.time.LocalDate

/** One sell operation's FIFO result, or that of the share of a conversion that its cash paid for.
  * The ledger has no exchange rates, so the gain itself is left to [[TaxReport]]: `proceeds` less
  * the cost in `currency`, and less every other currency's cost, each converted on `sellDate`.
  *
  * @param quantity
  *   the amount actually matched against open lots; can be less than the sell operation's own
  *   quantity when the account sold more than it had on record. For a conversion, the matched units
  *   that the cash paid for
  * @param currency
  *   the currency of the sell operation, or of the cash a conversion paid, which `proceeds` is in
  * @param proceeds
  *   what the sale brought in, less the share of the day's fees that were paid in `currency`,
  *   whatever the matched lots cost
  * @param costBasis
  *   the cost of the consumed lots in each currency it was paid in, with no zero amount, and so
  *   empty for a sale matched against no lot or against lots that cost nothing. A fee paid in a
  *   currency that the instrument was not sold in that day is added to it in that currency
  * @param holdingDays
  *   the matched quantity's weighted average holding period across every lot the sell consumed; `0`
  *   when nothing was matched
  */
final case class RealizedGain(
  instrument: InstrumentKey,
  ticker: Option[Ticker],
  sellDate: LocalDate,
  quantity: BigDecimal,
  currency: Currency,
  proceeds: BigDecimal,
  costBasis: Map[Currency, BigDecimal],
  holdingDays: Long
)
