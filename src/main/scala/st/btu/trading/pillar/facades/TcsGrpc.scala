package st.btu.trading.pillar.facades

import cats.effect.{Resource, Sync}
import cats.syntax.show.*
import fs2.grpc.syntax.all.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.{ManagedChannel, Metadata}
import st.btu.trading.pillar.models.tcs

// Tinkoff Invest API gRPC wiring shared by TcsFacadeImpl and gateways.tcs.TcsMarketDataSource:
// both connect to the same public endpoint over TLS and authenticate every call the same way, a
// Bearer token in the Authorization header.
private[pillar] object TcsGrpc {

  val ApiHost = "invest-public-api.tinkoff.ru"
  val ApiPort = 443

  def channel[I[_]: Sync]: Resource[I, ManagedChannel] =
    NettyChannelBuilder.forAddress(ApiHost, ApiPort).useTransportSecurity().resource[I]

  def bearerMetadata(token: tcs.Token): Metadata = {
    val md = new Metadata()
    val authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
    md.put(authKey, s"Bearer ${token.show}")
    md
  }
}
