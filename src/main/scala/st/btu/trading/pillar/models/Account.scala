package st.btu.trading.pillar.models

import st.btu.trading.pillar.models.domain.{AccountId, Broker}

import java.time.ZonedDateTime

/** A trading account: the owner of its watchlist, alert rules, operations and valuations.
  *
  * @param broker
  *   the broker the account is held at, which decides where its operations come from
  */
final case class Account(id: AccountId, name: String, broker: Broker, createdAt: ZonedDateTime)
