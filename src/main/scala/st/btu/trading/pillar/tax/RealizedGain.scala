package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.InstrumentKey
import st.btu.trading.pillar.models.market.Ticker

import java.time.LocalDate

/** One sell operation's FIFO result.
  *
  * @param quantity
  *   the amount actually matched against open lots; can be less than the sell operation's own
  *   quantity when the account sold more than it had on record
  * @param holdingDays
  *   the matched quantity's weighted average holding period across every lot the sell consumed; `0`
  *   when nothing was matched
  */
final case class RealizedGain(
  instrument: InstrumentKey,
  ticker: Option[Ticker],
  sellDate: LocalDate,
  quantity: BigDecimal,
  proceeds: BigDecimal,
  costBasis: BigDecimal,
  gain: BigDecimal,
  holdingDays: Long
)
