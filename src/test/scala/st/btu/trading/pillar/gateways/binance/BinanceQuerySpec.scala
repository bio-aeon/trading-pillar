package st.btu.trading.pillar.gateways.binance

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.SecretKey
import st.btu.trading.pillar.security.{Bouncy, CryptoImpl}

class BinanceQuerySpec extends Specification with CatsEffect {

  private implicit val bouncy: Bouncy = new Bouncy {}
  private val crypto = CryptoImpl.create[IO]

  // The secret of Binance's own example of signing a request.
  private val secretKey =
    SecretKey("NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j")

  "BinanceQuery" >> {
    "percentEncode" >> {
      "leaves the characters RFC 3986 calls unreserved as they are" >> {
        BinanceQuery.percentEncode("AZaz09-._~") must_== "AZaz09-._~"
      }

      "writes every other byte as a percent sign and two upper-case hexadecimal digits" >> {
        BinanceQuery.percentEncode("a b/c+d:&=") must_== "a%20b%2Fc%2Bd%3A%26%3D"
      }

      "writes a character outside ASCII as the bytes of its UTF-8 form" >> {
        BinanceQuery.percentEncode("é€") must_== "%C3%A9%E2%82%AC"
      }
    }

    "encode" >> {
      "joins the parameters in the order given, encoding names and values" >> {
        BinanceQuery.encode(List("symbol" -> "LTCBTC", "note" -> "a b", "x y" -> "1")) must_==
          "symbol=LTCBTC&note=a%20b&x%20y=1"
      }

      "gives nothing for no parameters" >> {
        BinanceQuery.encode(Nil) must_== ""
      }
    }

    "signed" >> {
      "appends the signature of Binance's documented example" >> {
        val parameters = List(
          "symbol" -> "LTCBTC",
          "side" -> "BUY",
          "type" -> "LIMIT",
          "timeInForce" -> "GTC",
          "quantity" -> "1",
          "price" -> "0.1",
          "recvWindow" -> "5000",
          "timestamp" -> "1499827319559"
        )
        BinanceQuery.signed(parameters, secretKey, crypto).map { signed =>
          signed must_==
            "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&" +
              "recvWindow=5000&timestamp=1499827319559&" +
              "signature=c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71"
        }
      }

      "signs the percent-encoded query and not the values as given" >> {
        val parameters =
          List("symbol" -> "LTCBTC", "note" -> "a b/c+d:é&=", "timestamp" -> "1499827319559")
        BinanceQuery.signed(parameters, secretKey, crypto).map { signed =>
          signed must_==
            "symbol=LTCBTC&note=a%20b%2Fc%2Bd%3A%C3%A9%26%3D&timestamp=1499827319559&" +
              "signature=5c435ed33d3f3b288199563cdf4292035e834130ea2fd2373be6ec7de7e1cd94"
        }
      }
    }
  }
}
