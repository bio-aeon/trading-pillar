package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.{BrokerInstrument, Ticker}
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.BinanceFakes.*

import java.time.{Instant, ZoneOffset, ZonedDateTime}

class BinanceInstrumentsSpec extends Specification {

  private val updatedAt = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, ZoneOffset.UTC)
  private val quotes = NonEmptyList.of(asset("USDC"), asset("USDT"))

  // An executed buy of the coin, which names its instrument.
  private def naming(coin: String): AccountOperation =
    accountOperation(
      AccountId(1),
      OperationKind.Buy,
      -1,
      Instant.parse("2026-03-10T11:00:00Z"),
      quantity = 1
    ).copy(instrument = Some(InstrumentKey(Broker.Binance, BrokerInstrumentId(coin))))

  private def instrumentsOf(
    coins: Set[String],
    names: Map[String, String] = Map.empty,
    listed: Set[String] = Set.empty
  ) =
    BinanceInstruments.of(
      coins.map(asset),
      names.map { case (code, name) => asset(code) -> name },
      listed.map(symbol),
      quotes,
      updatedAt
    )

  "BinanceInstruments" >> {
    "of" >> {
      "names a coin by its pair with the first price quote that Binance lists, and prices it in that quote" >> {
        val found = instrumentsOf(Set("BTC"), listed = Set("BTCUSDC", "BTCUSDT"))
        found.map(i => (i.ticker, i.currency)) must_==
          List((Ticker("BTCUSDC"), Currency("USDC")))
      }

      "falls back to a later price quote when Binance lists no pair with the first" >> {
        val found = instrumentsOf(Set("ETH"), listed = Set("ETHUSDT"))
        found.map(i => (i.ticker, i.currency)) must_==
          List((Ticker("ETHUSDT"), Currency("USDT")))
      }

      "names a coin that no price quote lists by its pair with the first quote" >> {
        val found = instrumentsOf(Set("SOL"))
        found.map(i => (i.ticker, i.currency)) must_==
          List((Ticker("SOLUSDC"), Currency("USDC")))
      }

      "describes a coin as crypto, with no board, named as Binance names it" >> {
        val found = instrumentsOf(Set("BTC"), names = Map("BTC" -> "Bitcoin"))
        found must_== List(
          BrokerInstrument(
            InstrumentKey(Broker.Binance, BrokerInstrumentId("BTC")),
            Ticker("BTCUSDC"),
            None,
            "Bitcoin",
            "crypto",
            Currency("USDC"),
            updatedAt
          )
        )
      }

      "names a coin that Binance gives no name by its asset code" >> {
        instrumentsOf(Set("BTC")).map(_.name) must_== List("BTC")
      }

      "describes the coins ordered by asset code" >> {
        instrumentsOf(Set("ETH", "BTC")).map(_.ticker) must_==
          List(Ticker("BTCUSDC"), Ticker("ETHUSDC"))
      }
    }

    "coinsOf" >> {
      "is each coin the operations name, once" >> {
        BinanceInstruments.coinsOf(List(naming("ETH"), naming("BTC"), naming("ETH"))) must_==
          Set(asset("BTC"), asset("ETH"))
      }

      "leaves out operations that name no instrument, and instruments of other brokers" >> {
        val cash = accountOperation(
          AccountId(1),
          OperationKind.Deposit,
          100,
          Instant.parse("2026-03-10T11:00:00Z"),
          currency = "USDT"
        )
        val atTinkoff = accountOperation(
          AccountId(1),
          OperationKind.Buy,
          -1,
          Instant.parse("2026-03-10T11:00:00Z"),
          instrument = Some("BBG004730N88"),
          quantity = 1
        )
        BinanceInstruments.coinsOf(List(cash, atTinkoff)) must beEmpty
      }
    }
  }
}
