package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.{BinanceAsset, BinanceBalances}
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.services.BinanceReconciliation.Difference
import st.btu.trading.pillar.testkit.BinanceFakes.{asset, pairOf}

class BinanceReconciliationSpec extends Specification {

  private val cashAssets = Set(asset("USDT"), asset("USDC"))

  private val listed =
    List(pairOf("BTC", "USDT"), pairOf("ETH", "BTC"), pairOf("BNB", "USDT"), pairOf("LDO", "USDT"))

  private def position(code: String): InstrumentKey =
    InstrumentKey(Broker.Binance, BrokerInstrumentId(code))

  private def positions(held: (String, BigDecimal)*): Map[InstrumentKey, BigDecimal] =
    held.map { case (code, quantity) => position(code) -> quantity }.toMap

  private def cash(held: (String, BigDecimal)*): Map[Currency, BigDecimal] =
    held.map { case (code, amount) => Currency(code) -> amount }.toMap

  private def balances(
    spot: Map[BinanceAsset, BigDecimal] = Map.empty,
    funding: Map[BinanceAsset, BigDecimal] = Map.empty
  ): BinanceBalances = BinanceBalances(spot, funding)

  private def wallet(held: (String, BigDecimal)*): Map[BinanceAsset, BigDecimal] =
    held.map { case (code, amount) => asset(code) -> amount }.toMap

  private def differences(
    held: Map[InstrumentKey, BigDecimal] = Map.empty,
    recordedCash: Map[Currency, BigDecimal] = Map.empty,
    reported: BinanceBalances = balances()
  ): List[Difference] =
    BinanceReconciliation.differences(held, recordedCash, reported, cashAssets, listed)

  "BinanceReconciliation" >> {
    "differences" >> {
      "lists a coin whose recorded position is not the balance Binance reports" >> {
        differences(
          positions("BTC" -> 1),
          reported = balances(wallet("BTC" -> BigDecimal("0.9")))
        ) must_==
          List(Difference(asset("BTC"), 1, BigDecimal("0.9")))
      }

      "lists a cash asset whose recorded cash is not the balance Binance reports" >> {
        differences(
          recordedCash = cash("USDT" -> 100),
          reported = balances(wallet("USDT" -> 99))
        ) must_== List(Difference(asset("USDT"), 100, 99))
      }

      "lists nothing when every balance matches" >> {
        differences(
          positions("BTC" -> 1),
          cash("USDT" -> 100),
          balances(wallet("BTC" -> 1, "USDT" -> 100))
        ) must beEmpty
      }

      "compares exactly, whatever the scale of the amounts" >> {
        differences(
          positions("BTC" -> BigDecimal("0.100000000000000000")),
          reported = balances(wallet("BTC" -> BigDecimal("0.1")))
        ) must beEmpty
      }

      "compares a coin with its position and not with cash, and a cash asset with cash and not with a position" >> {
        differences(
          positions("USDT" -> 5, "BTC" -> 1),
          cash("BTC" -> 7, "USDT" -> 100),
          balances(wallet("BTC" -> 1, "USDT" -> 100))
        ) must beEmpty
      }

      "counts an asset that only Binance reports as recorded at zero" >> {
        differences(reported = balances(wallet("DOGE" -> 5))) must_==
          List(Difference(asset("DOGE"), 0, 5))
      }

      "counts a coin that only the account recorded as reported at zero" >> {
        differences(positions("ETH" -> 2)) must_== List(Difference(asset("ETH"), 2, 0))
      }

      "counts a cash asset that only the account recorded as reported at zero" >> {
        differences(recordedCash = cash("USDC" -> 7)) must_== List(Difference(asset("USDC"), 7, 0))
      }

      "does not list an asset that Binance reports at zero and the account holds none of" >> {
        differences(reported = balances(wallet("ETH" -> 0))) must beEmpty
      }

      "adds up the spot and the funding wallet" >> {
        differences(
          recordedCash = cash("USDT" -> 100),
          reported = balances(wallet("USDT" -> 60), wallet("USDT" -> 40))
        ) must beEmpty
      }

      "reports the sum of the two wallets as what Binance holds" >> {
        differences(
          recordedCash = cash("USDT" -> 100),
          reported = balances(wallet("USDT" -> 60), wallet("USDT" -> 30))
        ) must_== List(Difference(asset("USDT"), 100, 90))
      }

      "leaves out a Simple Earn token of the spot wallet, which no pair trades while its asset is traded" >> {
        differences(reported = balances(wallet("LDBTC" -> 5, "LDUSDT" -> 7))) must beEmpty
      }

      "keeps a coin whose name starts with LD when a pair trades it" >> {
        differences(reported = balances(wallet("LDO" -> 3))) must_==
          List(Difference(asset("LDO"), 0, 3))
      }

      "keeps an asset named LD and another when no pair trades the other asset" >> {
        differences(reported = balances(wallet("LDXYZ" -> 3))) must_==
          List(Difference(asset("LDXYZ"), 0, 3))
      }

      "keeps a token named like Simple Earn's in the funding wallet, which has none" >> {
        differences(reported = balances(funding = wallet("LDBTC" -> 5))) must_==
          List(Difference(asset("LDBTC"), 0, 5))
      }

      "orders the differences by asset" >> {
        differences(
          positions("ETH" -> 1, "BTC" -> 1),
          cash("USDT" -> 1),
          balances(wallet("DOGE" -> 1))
        ).map(_.asset) must_== List(asset("BTC"), asset("DOGE"), asset("ETH"), asset("USDT"))
      }
    }
  }
}
