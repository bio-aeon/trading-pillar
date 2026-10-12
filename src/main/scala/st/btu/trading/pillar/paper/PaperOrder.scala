package st.btu.trading.pillar.paper

import st.btu.trading.pillar.alerts.StrategyBindingId
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.strategy.Side

import java.time.ZonedDateTime

/** A virtual order placed by [[PaperBroker]] for a paper-mode strategy binding. `filledAt`,
  * `fillPrice` and `commission` are set only when `status == Filled`.
  *
  * @param id
  *   `None` until storage assigns one
  * @param bindingId
  *   `None` only if the storage layer could not resolve one (should not happen in practice: every
  *   execution is triggered by a specific binding)
  * @param quantity
  *   the resolved whole-share count; `0` when rejected before a quantity could be meaningfully
  *   resolved (e.g. no paper account)
  */
final case class PaperOrder(
  id: Option[PaperOrderId],
  accountId: AccountId,
  bindingId: Option[StrategyBindingId],
  ticker: Ticker,
  side: Side,
  quantity: Long,
  requestedAt: ZonedDateTime,
  filledAt: Option[ZonedDateTime],
  fillPrice: Option[BigDecimal],
  commission: Option[BigDecimal],
  status: PaperOrderStatus,
  reason: Option[String]
)
