package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.{Currency, OperationRef}

import java.time.LocalDate

/** What the owner paid for the units that an executed `security_in` brought into the account, as no
  * broker can say: a coin deposited from another wallet, or shares transferred from another broker.
  * Without one, the lot of a transfer in costs the operation's own price, or nothing when it has
  * none.
  *
  * @param ref
  *   the `ref` of the `security_in` operation the cost is for; a cost whose ref names no executed
  *   transfer in of an instrument is ignored
  * @param cost
  *   the total cost of the units the operation brought in, not a cost per unit; zero for units that
  *   cost nothing, which still carries `acquiredOn`
  * @param currency
  *   the currency the cost was paid in, in which the lot keeps it
  * @param acquiredOn
  *   the day the units were acquired where it is not the day of the transfer, which decides how
  *   long they have been held and where the lot goes among the others of the instrument; the day of
  *   the operation when it is absent
  */
final case class TransferCost(
  ref: OperationRef,
  cost: BigDecimal,
  currency: Currency,
  acquiredOn: Option[LocalDate]
)
