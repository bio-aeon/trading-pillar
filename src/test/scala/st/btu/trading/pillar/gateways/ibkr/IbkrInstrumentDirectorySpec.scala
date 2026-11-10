package st.btu.trading.pillar.gateways.ibkr

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, Ticker}
import st.btu.trading.pillar.testkit.{IbkrFakes, MarketDataFakes}

import java.time.{ZoneOffset, ZonedDateTime}

class IbkrInstrumentDirectorySpec extends Specification with CatsEffect {

  private val vodafone = BrokerInstrument(
    IbkrFakes.instrumentKey("12345"),
    Ticker("VOD"),
    Some(ClassCode("LSE")),
    "VODAFONE GROUP PLC",
    "STK",
    Currency("GBP"),
    ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
  )

  "IbkrInstrumentDirectory" >> {
    "byKey" >> {
      "answers a contract id from the storage" >> {
        for {
          storage <- MarketDataFakes.brokerInstrumentStorage(List(vodafone))
          found <- IbkrInstrumentDirectory.create[IO](storage).byKey(vodafone.instrument)
        } yield found must beSome(vodafone)
      }

      "answers nothing for a contract id the storage lacks" >> {
        for {
          storage <- MarketDataFakes.brokerInstrumentStorage(List(vodafone))
          found <- IbkrInstrumentDirectory.create[IO](storage).byKey(IbkrFakes.instrumentKey("999"))
        } yield found must beNone
      }

      "answers nothing for a key of another broker, even one with the same id" >> {
        val tinkoffKey = InstrumentKey(Broker.Tinkoff, BrokerInstrumentId("12345"))
        for {
          storage <- MarketDataFakes.brokerInstrumentStorage(
            List(vodafone.copy(instrument = tinkoffKey))
          )
          found <- IbkrInstrumentDirectory.create[IO](storage).byKey(tinkoffKey)
        } yield found must beNone
      }
    }
  }
}
