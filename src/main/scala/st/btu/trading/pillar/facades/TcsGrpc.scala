package st.btu.trading.pillar.facades

import cats.effect.{Resource, Sync}
import cats.syntax.flatMap.*
import cats.syntax.show.*
import fs2.grpc.syntax.all.*
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyChannelBuilder}
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.{HttpConnectProxiedSocketAddress, ManagedChannel, Metadata, ProxyDetector}
import st.btu.trading.pillar.models.{HttpProxy, tcs}

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, SocketAddress}
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.net.ssl.{TrustManagerFactory, X509TrustManager}
import scala.util.Using

// Tinkoff Invest API gRPC wiring shared by every Tinkoff client: they connect to the same public
// endpoint over TLS and authenticate every call the same way, a Bearer token in the Authorization
// header.
private[pillar] object TcsGrpc {

  val ApiHost = "invest-public-api.tbank.ru"
  val ApiPort = 443

  // The API's certificates chain to the Russian Trusted Root CA, which the JDK does not ship. The
  // channel trusts it next to the JDK's own roots, for this endpoint only.
  private val RussianTrustedRootCa = "russian-trusted-root-ca.pem"

  /** A channel to the API, tunnelled through `proxy` with `CONNECT` when one is given and direct
    * otherwise, whatever the JVM's proxy settings say.
    */
  def channel[I[_]: Sync](proxy: Option[HttpProxy]): Resource[I, ManagedChannel] =
    Resource
      .eval(sslContext[I])
      .flatMap(ssl =>
        NettyChannelBuilder
          .forAddress(ApiHost, ApiPort)
          .sslContext(ssl)
          .proxyDetector(proxyDetector(proxy))
          .resource[I]
      )

  // Without a proxy a detector is still set: grpc-java's default one reads the JVM's proxy
  // properties and GRPC_PROXY_EXP. The proxy's host is resolved on every call, not once, so a
  // proxy that restarts at a new address is found again; failing to resolve it, or meeting a
  // target of another kind, fails the call rather than connecting directly.
  private[facades] def proxyDetector(proxy: Option[HttpProxy]): ProxyDetector =
    proxy match {
      case None => (_: SocketAddress) => null
      case Some(p) =>
        (target: SocketAddress) =>
          target match {
            case address: InetSocketAddress =>
              HttpConnectProxiedSocketAddress
                .newBuilder()
                .setTargetAddress(address)
                .setProxyAddress(
                  new InetSocketAddress(InetAddress.getByName(p.host.toString), p.port.value)
                )
                .build()
            case other =>
              throw new IOException(s"cannot tunnel a connection to $other through a proxy")
          }
    }

  def bearerMetadata(token: tcs.Token): Metadata = {
    val md = new Metadata()
    val authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
    md.put(authKey, s"Bearer ${token.show}")
    md
  }

  private[facades] def trustedRoots[I[_]: Sync]: I[Vector[X509Certificate]] =
    Sync[I].blocking(jdkRoots :+ russianTrustedRoot)

  private def sslContext[I[_]: Sync]: I[SslContext] =
    trustedRoots[I].flatMap(roots =>
      Sync[I].blocking(GrpcSslContexts.forClient().trustManager(roots*).build())
    )

  private def jdkRoots: Vector[X509Certificate] = {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    factory.init(null: KeyStore)
    factory.getTrustManagers.toVector.collect { case manager: X509TrustManager =>
      manager.getAcceptedIssuers.toVector
    }.flatten
  }

  private def russianTrustedRoot: X509Certificate = {
    val stream = Option(getClass.getResourceAsStream(RussianTrustedRootCa))
      .getOrElse(
        throw new IllegalStateException(s"$RussianTrustedRootCa is missing from the classpath")
      )
    Using.resource(stream)(CertificateFactory.getInstance("X.509").generateCertificate) match {
      case certificate: X509Certificate => certificate
      case other =>
        throw new IllegalStateException(
          s"$RussianTrustedRootCa holds a ${other.getType} certificate"
        )
    }
  }
}
