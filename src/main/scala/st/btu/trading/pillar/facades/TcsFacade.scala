package st.btu.trading.pillar.facades

import ru.tinkoff.piapi.contract.v1.operations.OperationItem

import java.time.ZonedDateTime

/** The calls trading-pillar makes to one Tinkoff brokerage account. */
trait TcsFacade[F[_]] {

  /** Every operation of the account dated between `since` and `until`, in any state, with the
    * broker's commissions included.
    */
  def operations(since: ZonedDateTime, until: ZonedDateTime): F[List[OperationItem]]
}

object TcsFacade {

  /** The T-Invest contracts version operations are fetched with; every synced operation is stored
    * with it as its protocol version.
    */
  val ApiVersion: String = ContractsInfo.investContracts
}
