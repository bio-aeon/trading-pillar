package st.btu.trading.pillar.models.market

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, InstrumentKey}
import st.btu.trading.pillar.testkit.MarketDataFakes.brokerInstrument

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
  }
}
