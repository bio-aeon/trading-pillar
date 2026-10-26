package st.btu.trading.pillar.facades

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification

import java.security.MessageDigest
import java.security.cert.X509Certificate

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

  "TcsGrpc" >> {
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
