package st.btu.trading.pillar.models.binance

import org.specs2.mutable.Specification
import st.btu.trading.pillar.testkit.BinanceFakes.readOnlyKey

class BinanceKeyPermissionsSpec extends Specification {

  "BinanceKeyPermissions" >> {
    "readOnly" >> {
      "accepts a key that reads and has no other permission" >> {
        readOnlyKey.readOnly must beRight(())
      }

      "accepts a key that names no other permission at all" >> {
        readOnlyKey.copy(otherFlags = Map.empty).readOnly must beRight(())
      }

      "refuses a key that may trade, naming the permission" >> {
        val trading = readOnlyKey.copy(otherFlags =
          readOnlyKey.otherFlags.updated("enableSpotAndMarginTrading", true)
        )
        trading.readOnly must beLeft[BinanceKeyError](
          BinanceKeyError.MoreThanReading(List("enableSpotAndMarginTrading"))
        )
      }

      "refuses a key that may withdraw or transfer, naming every permission it has" >> {
        val dangerous = readOnlyKey.copy(otherFlags =
          readOnlyKey.otherFlags ++ Map(
            "enableWithdrawals" -> true,
            "permitsUniversalTransfer" -> true
          )
        )
        dangerous.readOnly must beLeft[BinanceKeyError](
          BinanceKeyError.MoreThanReading(List("enableWithdrawals", "permitsUniversalTransfer"))
        )
      }

      "refuses a permission it has not heard of, naming it" >> {
        val unknown =
          readOnlyKey.copy(otherFlags = readOnlyKey.otherFlags.updated("enableCopyTrading", true))
        unknown.readOnly must beLeft[BinanceKeyError](
          BinanceKeyError.MoreThanReading(List("enableCopyTrading"))
        )
      }

      "refuses a key that may not read" >> {
        readOnlyKey.copy(enableReading = false).readOnly must beLeft[BinanceKeyError](
          BinanceKeyError.ReadingNotEnabled
        )
      }

      "names the permissions of a key that may not read and may do more" >> {
        val both = readOnlyKey.copy(
          enableReading = false,
          otherFlags = readOnlyKey.otherFlags.updated("enableWithdrawals", true)
        )
        both.readOnly must beLeft[BinanceKeyError](
          BinanceKeyError.MoreThanReading(List("enableWithdrawals"))
        )
      }
    }
  }
}
