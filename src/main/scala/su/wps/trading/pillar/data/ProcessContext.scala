package su.wps.trading.pillar.data

import derevo.derive
import su.wps.trading.pillar.models.domain.AccountId
import tofu.logging.derivation.loggable

@derive(loggable)
final case class ProcessContext(traceId: String, accountId: AccountId)
