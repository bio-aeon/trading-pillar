package st.btu.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Broker
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.brokerInstrument

class RoutingInstrumentDirectorySpec extends Specification with CatsEffect {

  private val sberbank = brokerInstrument("BBG004730N88", Ticker("SBER"))

  "RoutingInstrumentDirectory" >> {
    "byKey" >> {

      "answers a key from the directory of its broker" >> {
        for {
          tinkoff <- MarketDataFakes.instrumentDirectory(List(sberbank))
          routing = RoutingInstrumentDirectory.create[IO](Map(Broker.Tinkoff -> tinkoff))
          found <- routing.byKey(sberbank.instrument)
        } yield found must beSome(sberbank)
      }

      "answers nothing for a broker it has no directory for" >> {
        RoutingInstrumentDirectory
          .create[IO](Map.empty)
          .byKey(sberbank.instrument)
          .map(_ must beNone)
      }
    }
  }
}
