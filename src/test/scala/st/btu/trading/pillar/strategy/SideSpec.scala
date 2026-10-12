package st.btu.trading.pillar.strategy

import io.circe.syntax.*
import org.specs2.mutable.Specification

class SideSpec extends Specification {

  "Side" >> {
    "name" >> {
      "is \"buy\" for Buy and \"sell\" for Sell" >> {
        Side.Buy.name must_== "buy"
        Side.Sell.name must_== "sell"
      }
    }

    "fromName" >> {
      "is the inverse of name for every case" >> {
        Side.fromName("buy") must_== Some(Side.Buy)
        Side.fromName("sell") must_== Some(Side.Sell)
      }

      "returns nothing for any other string" >> {
        Side.fromName("BUY") must beNone
        Side.fromName("") must beNone
      }
    }

    "circe codec" >> {
      "encodes to the lowercase name and decodes back to the same value" >> {
        (Side.Buy: Side).asJson.noSpaces must_== "\"buy\""
        io.circe.parser.decode[Side]("\"sell\"") must beRight(Side.Sell: Side)
      }

      "fails to decode an unrecognized string" >> {
        io.circe.parser.decode[Side]("\"hold\"") must beLeft
      }
    }
  }
}
