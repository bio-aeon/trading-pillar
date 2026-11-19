package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import org.specs2.mutable.Specification
import st.btu.trading.pillar.testkit.BinanceFakes.asset

import scala.util.Try

class BinanceSyncConfigSpec extends Specification {

  "BinanceSyncConfig" >> {
    "construction" >> {
      "rejects a price quote that is not a cash asset" >> {
        Try(BinanceSyncConfig(priceQuotes = NonEmptyList.of(asset("BTC")))).isFailure must beTrue
      }

      "accepts a price quote that has been added to the cash assets" >> {
        val config = BinanceSyncConfig(
          cashAssets = BinanceSyncConfig.DefaultCashAssets + asset("EUR"),
          priceQuotes = NonEmptyList.of(asset("EUR"), asset("USDT"))
        )
        config.priceQuotes must_== NonEmptyList.of(asset("EUR"), asset("USDT"))
      }

      "rejects a negative number of days to read again" >> {
        Try(BinanceSyncConfig(rereadDays = -1)).isFailure must beTrue
      }

      "accepts no days to read again" >> {
        Try(BinanceSyncConfig(rereadDays = 0)).isSuccess must beTrue
      }
    }
  }
}
