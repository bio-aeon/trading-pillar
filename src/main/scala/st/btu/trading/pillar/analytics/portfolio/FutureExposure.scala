package st.btu.trading.pillar.analytics.portfolio

/** How much a futures position controls: its contracts times the contract's price times the money a
  * point of that price is worth. A future is valued at zero whatever its exposure, since its gain
  * or loss reaches cash as variation margin.
  */
enum FutureExposure {

  /** The broker gave no terms for the contract, so a point has no value. */
  case TermsUnknown

  /** The terms are known but there is no close or no exchange rate to put an amount on them.
    *
    * @param pointValue
    *   the money one point of the price is worth, in the position's own currency
    */
  case Unpriced(pointValue: BigDecimal)

  /** The exposure, negative for a short position.
    *
    * @param pointValue
    *   the money one point of the price is worth, in the position's own currency, unconverted
    * @param amount
    *   contracts times the last close times `pointValue`, converted to the snapshot's currency
    */
  case Known(pointValue: BigDecimal, amount: BigDecimal)
}
