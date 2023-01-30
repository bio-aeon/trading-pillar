package su.wps.trading.pillar.models

import cats.Show
import io.estatico.newtype.macros.newtype
import tofu.logging.Loggable

package object domain {

  @newtype final case class AccountId(value: Int)

  object AccountId {
    implicit val loggable: Loggable[AccountId] = deriving
  }

  @newtype final case class PortfolioOperationId(value: Long)
  @newtype final case class PortfolioOperationExtId(value: String)

  object PortfolioOperationExtId {
    implicit val show: Show[PortfolioOperationExtId] = deriving
  }

  @newtype final case class PortfolioOperationType(value: String)

  object PortfolioOperationType {
    implicit val show: Show[PortfolioOperationType] = deriving
  }

  @newtype final case class PortfolioInstrumentType(value: String)
  @newtype final case class PortfolioOperationStatus(value: String)
  @newtype final case class Figi(value: String)
  @newtype final case class Currency(value: String)
  @newtype final case class ProtocolVersion(value: String)
}
