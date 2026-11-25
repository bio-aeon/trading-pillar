package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.LocalDate

/** What a futures contract is worth per point of its price and until when it exists, as its broker
  * states it.
  *
  * @param underlying
  *   the asset the contract is on
  * @param lastTradeDate
  *   the last day the contract can be traded, an exchange day
  * @param expirationDate
  *   the day the contract ends, an exchange day
  * @param priceStep
  *   the smallest move of the contract's price, in points; positive
  * @param priceStepValue
  *   the money one price step is worth, in the currency of the contract's record; positive
  */
final case class FutureTerms(
  underlying: Underlying,
  lastTradeDate: LocalDate,
  expirationDate: LocalDate,
  priceStep: BigDecimal,
  priceStepValue: BigDecimal
) {

  /** The money one point of the contract's price is worth, in the currency of its record. */
  def pointValue: BigDecimal = divide(priceStepValue, priceStep)

  /** Whether the contract has ended by `date`: true once `date` is after the expiration date, so
    * false on that date itself.
    */
  def expiredOn(date: LocalDate): Boolean = date.isAfter(expirationDate)
}
