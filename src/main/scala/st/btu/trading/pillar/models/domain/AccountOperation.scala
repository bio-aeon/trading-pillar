package st.btu.trading.pillar.models.domain

import java.time.{Instant, LocalDate, ZoneOffset}

/** One change to an account at a broker, in terms shared by every broker. Each broker's adapter
  * translates that broker's own records into these.
  *
  * @param accountId
  *   the account the operation belongs to
  * @param ref
  *   the broker's id for the operation, unique within the account, so a later state of the same
  *   operation replaces the earlier one
  * @param state
  *   how far the operation has got; readers count executed operations only
  * @param occurredAt
  *   when the operation took place, as the broker dates it
  * @param instrument
  *   the instrument the operation concerns, if it concerns one
  * @param quantity
  *   the signed change of the position in executed units: positive for buy, security_in, a
  *   spin-off's new instrument, a stock dividend and a reward of units, negative for sell and
  *   security_out, of either sign for a split and for a conversion leg, and zero for every other
  *   kind, a spin-off's parent marker included
  * @param price
  *   the price per unit in `currency`, where the broker gives one; for a conversion leg, a stock
  *   dividend and a reward of units, the market value per unit
  * @param amount
  *   the signed change of cash in `currency`; a tax refund is positive
  * @param brokerType
  *   the broker's own name for the operation, for display
  * @param group
  *   the event the operation belongs to along with others, if it belongs to one: the legs of one
  *   corporate action share a group
  */
final case class AccountOperation(
  accountId: AccountId,
  ref: OperationRef,
  kind: OperationKind,
  state: OperationState,
  occurredAt: Instant,
  instrument: Option[InstrumentKey],
  quantity: BigDecimal,
  price: Option[BigDecimal],
  amount: BigDecimal,
  currency: Currency,
  brokerType: BrokerOperationType,
  group: Option[OperationGroup] = None
) {

  /** The UTC calendar date of `occurredAt`, the day every reader attributes the operation to. */
  def occurredOn: LocalDate = LocalDate.ofInstant(occurredAt, ZoneOffset.UTC)
}
