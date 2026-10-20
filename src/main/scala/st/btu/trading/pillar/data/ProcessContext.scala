package st.btu.trading.pillar.data

import st.btu.trading.pillar.models.domain.AccountId
import tofu.logging.Loggable
import tofu.logging.derivation.derived

/** What a process step runs in and logs with: the trace id that ties the step's log entries
  * together, and the account the step acts for.
  */
final case class ProcessContext(traceId: String, accountId: AccountId) derives Loggable
