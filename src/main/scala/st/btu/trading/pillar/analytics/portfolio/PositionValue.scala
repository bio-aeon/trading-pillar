package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.Figi
import st.btu.trading.pillar.models.market.Ticker

/** `name`/`instrumentType`/`currency`/`exchange` are `None` together only when `figi` is unmapped;
  * `price`/`value`/`weight` can still be `None` separately on a mapped but unpriced position.
  *
  * @param price
  *   the last known close in this position's own `currency`, unconverted
  * @param value
  *   this position's contribution to `PositionSnapshot.total`, converted to the snapshot's base
  *   currency; need not equal `price * quantity` for a non-base-currency position
  * @param weight
  *   `value / total`; `None` whenever `value` is, or on a zero-total snapshot
  */
final case class PositionValue(
  figi: Figi,
  ticker: Option[Ticker],
  name: Option[String],
  instrumentType: Option[String],
  currency: Option[String],
  exchange: Option[String],
  quantity: Int,
  price: Option[BigDecimal],
  value: Option[BigDecimal],
  weight: Option[BigDecimal]
)
