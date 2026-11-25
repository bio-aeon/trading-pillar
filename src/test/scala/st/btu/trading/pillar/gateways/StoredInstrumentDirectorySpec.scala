package st.btu.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.{BrokerInstrument, Ticker}
import st.btu.trading.pillar.testkit.MarketDataFakes

import java.time.{ZoneOffset, ZonedDateTime}

class StoredInstrumentDirectorySpec extends Specification with CatsEffect {

  private val bitcoin = BrokerInstrument(
    InstrumentKey(Broker.Binance, BrokerInstrumentId("BTC")),
    Ticker("BTCUSDC"),
    None,
    "Bitcoin",
    "crypto",
    Currency("USDC"),
    ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
    futureTerms = None
  )

  "StoredInstrumentDirectory" >> {
    "byKey" >> {
      "answers a key of its broker from the storage" >> {
        for {
          storage <- MarketDataFakes.brokerInstrumentStorage(List(bitcoin))
          directory = StoredInstrumentDirectory.create[IO](Broker.Binance, storage)
          found <- directory.byKey(bitcoin.instrument)
        } yield found must beSome(bitcoin)
      }

      "answers nothing for a key of its broker that the storage lacks" >> {
        val unknown = InstrumentKey(Broker.Binance, BrokerInstrumentId("DOGE"))
        for {
          storage <- MarketDataFakes.brokerInstrumentStorage(List(bitcoin))
          found <- StoredInstrumentDirectory.create[IO](Broker.Binance, storage).byKey(unknown)
        } yield found must beNone
      }

      "answers nothing for a key of another broker, though the storage holds it" >> {
        val tinkoffKey = InstrumentKey(Broker.Tinkoff, BrokerInstrumentId("BTC"))
        for {
          storage <- MarketDataFakes.brokerInstrumentStorage(
            List(bitcoin.copy(instrument = tinkoffKey))
          )
          found <- StoredInstrumentDirectory.create[IO](Broker.Binance, storage).byKey(tinkoffKey)
        } yield found must beNone
      }
    }
  }
}
