package st.btu.trading.pillar.facades

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.comcast.ip4s.*
import io.grpc.HttpConnectProxiedSocketAddress
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.HttpProxy

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.security.MessageDigest
import java.security.cert.X509Certificate
import scala.concurrent.duration.*

class TcsGrpcSpec extends Specification with CatsEffect {

  // As published by the Russian Ministry of Digital Development and in T-Bank's own Java SDK.
  private val RussianTrustedRootSha256 =
    "D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31"

  private def sha256(certificate: X509Certificate): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(certificate.getEncoded)
      .map(b => f"$b%02X")
      .mkString(":")

  private val Loopback = host"127.0.0.1"
  // Below the example's own timeout, so a channel that never connects fails the example with the
  // socket's timeout instead of being reported as skipped.
  private val WaitLimit = 5.seconds

  private val tinkoffAddress = InetSocketAddress.createUnresolved(TcsGrpc.ApiHost, TcsGrpc.ApiPort)

  // Accepts one connection and reads its first line, which the proxied channel opens with CONNECT.
  // The socket timeouts bound both waits, since IO cannot cancel a blocked accept or read.
  private def firstLineReceivedBy(server: ServerSocket): IO[String] =
    IO.blocking {
      server.setSoTimeout(WaitLimit.toMillis.toInt)
      val client = server.accept()
      try {
        client.setSoTimeout(WaitLimit.toMillis.toInt)
        new BufferedReader(new InputStreamReader(client.getInputStream)).readLine()
      } finally client.close()
    }

  "TcsGrpc" >> {
    "proxy detector" >> {
      "tunnels to the API's unresolved address through the proxy" >> {
        TcsGrpc
          .proxyDetector(Some(HttpProxy(Loopback, port"3128")))
          .proxyFor(tinkoffAddress) must beLike { case proxied: HttpConnectProxiedSocketAddress =>
          (proxied.getTargetAddress.isUnresolved must beTrue) and
            (proxied.getTargetAddress.getHostString must beEqualTo(TcsGrpc.ApiHost)) and
            (proxied.getTargetAddress.getPort must beEqualTo(443)) and
            (proxied.getProxyAddress must beEqualTo(
              new InetSocketAddress(InetAddress.getByName(Loopback.toString), 3128)
            ))
        }
      }

      "connects directly when no proxy is given" >> {
        TcsGrpc.proxyDetector(None).proxyFor(tinkoffAddress) must beNull
      }
    }

    "channel" >> {
      "opens its connection with CONNECT to the API's host when a proxy is given" >> {
        val fakeProxy =
          IO.blocking(new ServerSocket(0, 1, InetAddress.getByName(Loopback.toString)))
        fakeProxy.bracket { server =>
          TcsGrpc
            .channel[IO](Some(HttpProxy(Loopback, Port.fromInt(server.getLocalPort).get)))
            .use(channel => IO.blocking(channel.getState(true)) *> firstLineReceivedBy(server))
            .map(_ must beEqualTo(s"CONNECT ${TcsGrpc.ApiHost}:${TcsGrpc.ApiPort} HTTP/1.1"))
        }(server => IO.blocking(server.close()))
      }
    }

    "trusted roots" >> {
      "include the Russian Trusted Root CA the API's certificates chain to" >> {
        TcsGrpc
          .trustedRoots[IO]
          .map(roots => roots.map(sha256) must contain(RussianTrustedRootSha256))
      }

      "keep the JDK's own roots" >> {
        TcsGrpc.trustedRoots[IO].map { roots =>
          roots.map(_.getSubjectX500Principal.getName) must contain(
            "CN=ISRG Root X1,O=Internet Security Research Group,C=US"
          )
        }
      }
    }
  }
}
