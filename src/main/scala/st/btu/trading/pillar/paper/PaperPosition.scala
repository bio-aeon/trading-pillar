package st.btu.trading.pillar.paper

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker

import java.time.ZonedDateTime

/** One ticker's virtual position within a paper account, updated after every fill. */
final case class PaperPosition(
  accountId: AccountId,
  ticker: Ticker,
  quantity: Long,
  averageCost: BigDecimal,
  updatedAt: ZonedDateTime
)
