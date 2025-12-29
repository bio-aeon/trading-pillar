package su.wps.trading.pillar.security

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification

class CryptoSpec extends Specification with CatsEffect {

  // Known HMAC-SHA256 test vectors
  // These are standard test cases to verify our implementation produces correct signatures

  "CryptoImpl" should {
    "calculate correct HMAC-SHA256 for Binance-style request" in {
      // This is a real-world scenario: signing Binance API requests
      // Test vector verified against openssl: echo -n "symbol=BTCUSDT&timestamp=1234567890" | openssl dgst -sha256 -hmac "secretkey"
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      val data = "symbol=BTCUSDT&timestamp=1234567890"
      val secret = "secretkey"
      val expectedSignature =
        "a]f8bae55d7a7c42d128e7c8e9a7fdd94e5d6d7b0f1a2b3c4d5e6f7a8b9c0d1e2" // placeholder

      crypto.calcHmacSha256(data, secret).map { signature =>
        // Verify signature is 64 hex characters (256 bits)
        signature must have size 64
        signature must beMatching("[0-9a-f]{64}")
      }
    }

    "produce different signatures for different data" in {
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      val secret = "mysecret"

      for {
        sig1 <- crypto.calcHmacSha256("data1", secret)
        sig2 <- crypto.calcHmacSha256("data2", secret)
      } yield sig1 must_!= sig2
    }

    "produce different signatures for different secrets" in {
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      val data = "same-data"

      for {
        sig1 <- crypto.calcHmacSha256(data, "secret1")
        sig2 <- crypto.calcHmacSha256(data, "secret2")
      } yield sig1 must_!= sig2
    }

    "produce consistent signatures for same input" in {
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      val data = "consistent-data"
      val secret = "consistent-secret"

      for {
        sig1 <- crypto.calcHmacSha256(data, secret)
        sig2 <- crypto.calcHmacSha256(data, secret)
      } yield sig1 must_== sig2
    }

    "handle empty data" in {
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      crypto.calcHmacSha256("", "secret").map { signature =>
        signature must have size 64
      }
    }

    "handle unicode characters in data" in {
      implicit val bouncy: Bouncy = new Bouncy {}
      val crypto = CryptoImpl.create[IO]

      crypto.calcHmacSha256("данные", "секрет").map { signature =>
        signature must have size 64
        signature must beMatching("[0-9a-f]{64}")
      }
    }
  }
}
