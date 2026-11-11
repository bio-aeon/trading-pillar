package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.BrokerInstrumentId
import st.btu.trading.pillar.models.ibkr.{IbkrOpenPosition, IbkrOpenPositions}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.services.IbkrReconciliation.Difference
import st.btu.trading.pillar.testkit.IbkrFakes.instrumentKey

import java.time.{LocalDate, ZoneId}

class IbkrReconciliationSpec extends Specification {

  private def reported(positions: IbkrOpenPosition*): IbkrOpenPositions =
    IbkrOpenPositions(LocalDate.of(2026, 11, 6), ZoneId.of("America/New_York"), positions.toList)

  private def position(
    conid: String,
    symbol: String,
    quantity: BigDecimal,
    assetCategory: String = "STK"
  ): IbkrOpenPosition =
    IbkrOpenPosition(BrokerInstrumentId(conid), Some(Ticker(symbol)), Some(assetCategory), quantity)

  "IbkrReconciliation" >> {
    "differences" >> {
      "lists an instrument whose recorded and reported quantities differ, with IB's symbol" >> {
        val holdings = Map(instrumentKey("12345") -> BigDecimal(100))

        IbkrReconciliation.differences(holdings, reported(position("12345", "VOD", 90))) must_==
          List(Difference(instrumentKey("12345"), Some(Ticker("VOD")), 100, 90))
      }

      "lists nothing when every quantity matches" >> {
        val holdings = Map(instrumentKey("12345") -> BigDecimal(100))

        IbkrReconciliation.differences(holdings, reported(position("12345", "VOD", 100))) must
          beEmpty
      }

      "counts a contract that only IB reports as recorded at zero" >> {
        IbkrReconciliation.differences(Map.empty, reported(position("12345", "VOD", 5))) must_==
          List(Difference(instrumentKey("12345"), Some(Ticker("VOD")), 0, 5))
      }

      "counts a contract that only the account holds as reported at zero, with no symbol" >> {
        val holdings = Map(instrumentKey("12345") -> BigDecimal(5))

        IbkrReconciliation.differences(holdings, reported()) must_==
          List(Difference(instrumentKey("12345"), None, 5, 0))
      }

      "leaves out reported positions of another asset category" >> {
        IbkrReconciliation.differences(
          Map.empty,
          reported(position("777", "VOD 261218C00100000", 2, assetCategory = "OPT"))
        ) must beEmpty
      }

      "adds up the rows IB reports for one contract" >> {
        val holdings = Map(instrumentKey("12345") -> BigDecimal(100))

        IbkrReconciliation.differences(
          holdings,
          reported(position("12345", "VOD", 60), position("12345", "VOD", 40))
        ) must beEmpty
      }

      "orders the differences by contract" >> {
        val holdings =
          Map(instrumentKey("222") -> BigDecimal(1), instrumentKey("111") -> BigDecimal(1))

        IbkrReconciliation.differences(holdings, reported()).map(_.instrument) must_==
          List(instrumentKey("111"), instrumentKey("222"))
      }
    }
  }
}
