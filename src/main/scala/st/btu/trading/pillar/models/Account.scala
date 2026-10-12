package st.btu.trading.pillar.models

import st.btu.trading.pillar.models.domain.AccountId

import java.time.ZonedDateTime

final case class Account(id: AccountId, name: String, createdAt: ZonedDateTime)
