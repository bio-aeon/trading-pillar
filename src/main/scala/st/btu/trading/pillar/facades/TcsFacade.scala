package st.btu.trading.pillar.facades

import ru.tinkoff.piapi.contract.v1.operations.OperationItem
import st.btu.trading.pillar.models.domain.Portfolio

import java.time.ZonedDateTime

trait TcsFacade[F[_]] {
  def portfolio: F[Portfolio]

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
