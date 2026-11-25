package st.btu.trading.pillar.models.market

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, InstrumentKey}
import st.btu.trading.pillar.testkit.MarketDataFakes.brokerInstrument

import java.time.LocalDate

class BrokerInstrumentSpec extends Specification {

  "BrokerInstrument" >> {
    "isFuture" >> {
      "holds for a Tinkoff record of type futures" >> {
        brokerInstrument("FUT1", Ticker("SFH7"), instrumentType = "futures").isFuture must beTrue
      }

      "does not hold for a Tinkoff share" >> {
        brokerInstrument("F1", Ticker("SBER")).isFuture must beFalse
      }

      "does not hold for an Interactive Brokers futures contract, which is not read as one yet" >> {
        val contract = brokerInstrument("1", Ticker("ESZ6"), instrumentType = "FUT")
          .copy(instrument = InstrumentKey(Broker.Ibkr, BrokerInstrumentId("1")))

        contract.isFuture must beFalse
      }
    }

    "expiredOn" >> {
      val expiration = LocalDate.of(2027, 3, 18)
      val terms =
        FutureTerms(
          Underlying("SBER"),
          expiration,
          expiration,
          BigDecimal("0.01"),
          BigDecimal("0.79")
        )
      val contract = brokerInstrument(
        "FUT1",
        Ticker("SFH7"),
        instrumentType = "futures",
        futureTerms = Some(terms)
      )

      "holds the day after the expiration date of the terms" >> {
        contract.expiredOn(expiration.plusDays(1)) must beTrue
      }

      "does not hold on the expiration date" >> {
        contract.expiredOn(expiration) must beFalse
      }

      "does not hold for a future without terms, whatever the date" >> {
        contract.copy(futureTerms = None).expiredOn(expiration.plusYears(1)) must beFalse
      }
    }
  }
}
