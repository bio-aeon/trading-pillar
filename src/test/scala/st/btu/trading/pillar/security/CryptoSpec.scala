package st.btu.trading.pillar.security

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification

class CryptoSpec extends Specification with CatsEffect {

  "CryptoImpl.calcHmacSha256" >> {
    "computes the RFC 4231 test case 2 HMAC-SHA256 digest" >> {
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      crypto.calcHmacSha256("what do ya want for nothing?", "Jefe").map { signature =>
        signature must_== "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
      }
    }
  }
}
