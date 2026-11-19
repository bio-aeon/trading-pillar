package st.btu.trading.pillar.models.binance

import org.specs2.mutable.Specification

// The strings below are what consumers store: renaming one breaks the data already stored.
class PersistedBinanceValuesSpec extends Specification {

  "BinanceSource" >> {
    "persists as the strings a consumer stores" >> {
      BinanceSource.values.map(_.value) must contain(
        exactly(
          "trade",
          "deposit",
          "withdrawal",
          "fiat_deposit",
          "fiat_withdrawal",
          "fiat_buy",
          "fiat_sell",
          "convert"
        )
      )
    }
  }
}
