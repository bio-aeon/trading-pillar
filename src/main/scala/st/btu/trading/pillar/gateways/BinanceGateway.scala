package st.btu.trading.pillar.gateways

import io.circe.Json

trait BinanceGateway[F[_]] {
  def allOrders: F[Json]
}
