package su.wps.trading.pillar.models

import su.wps.trading.pillar.models.domain.AccountId

import java.time.ZonedDateTime

final case class Account(id: AccountId, name: String, createdAt: ZonedDateTime)
