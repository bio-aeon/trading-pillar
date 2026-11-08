package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.{Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.{Source, Ticker}

/** `ticker`/`name`/`instrumentType`/`currency`/`source` are `None` together only when `instrument`
  * is unmapped, and `exchange` is also `None` for a mapped position whose ticker has no stored
  * instrument metadata from its configured source; `price`/`value`/`weight`/`dayReturn` can still
  * be `None` separately on a mapped position, each as its own parameter says.
  *
  * @param instrument
  *   the instrument as the account's broker knows it
  * @param currency
  *   the currency the position is priced in
  * @param source
  *   the source the position's ticker is configured to use, whose bars priced it
  * @param quantity
  *   the units held, fractional for an instrument that trades in fractions
  * @param price
  *   the last known close in this position's own `currency`, unconverted
  * @param value
  *   this position's contribution to `PositionSnapshot.total`, converted to the snapshot's base
  *   currency; need not equal `price * quantity` for a non-base-currency position
  * @param weight
  *   `value / total`; `None` whenever `value` is, or on a zero-total snapshot
  * @param dayReturn
  *   the last close over the close of the bar before it, minus 1, as a fraction (`0.02` is +2%), in
  *   the position's own `currency`; both closes are on or before the snapshot date, and the field
  *   is `None` without two of them or when the earlier one is not positive
  */
final case class PositionValue(
  instrument: InstrumentKey,
  ticker: Option[Ticker],
  name: Option[String],
  instrumentType: Option[String],
  currency: Option[Currency],
  exchange: Option[String],
  source: Option[Source],
  quantity: BigDecimal,
  price: Option[BigDecimal],
  value: Option[BigDecimal],
  weight: Option[BigDecimal],
  dayReturn: Option[BigDecimal]
)
