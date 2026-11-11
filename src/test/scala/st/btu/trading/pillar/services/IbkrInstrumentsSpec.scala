package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{BrokerInstrumentId, Currency}
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, Ticker}
import st.btu.trading.pillar.testkit.IbkrFakes

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class IbkrInstrumentsSpec extends Specification {

  private val updatedAt = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC)

  private def security(
    conid: String,
    symbol: String = "VOD",
    exchange: Option[String] = Some("LSE"),
    description: Option[String] = Some("VODAFONE GROUP PLC"),
    assetCategory: Option[String] = Some("STK"),
    currency: Option[String] = Some("GBP")
  ): IbkrSecurityInfo =
    IbkrSecurityInfo(
      BrokerInstrumentId(conid),
      Ticker(symbol),
      exchange.map(ClassCode(_)),
      None,
      description,
      assetCategory,
      currency.map(Currency(_))
    )

  private def trade(conid: String, attributes: (String, String)*): IbkrReportedRow =
    IbkrReportedRow(
      IbkrSection.Trade,
      IbkrTransactionId(s"t$conid"),
      LocalDate.of(2026, 1, 5),
      (Map("conid" -> conid, "assetCategory" -> "STK") ++ attributes).toMap
    )

  private def statement(
    securities: List[IbkrSecurityInfo],
    rows: List[IbkrReportedRow] = Nil
  ): IbkrStatement =
    IbkrStatement(
      IbkrAccountId("U1111111"),
      LocalDate.of(2026, 1, 1),
      LocalDate.of(2026, 1, 31),
      rows,
      securities,
      Nil
    )

  private def vodafone(currency: String = "GBP"): BrokerInstrument =
    BrokerInstrument(
      IbkrFakes.instrumentKey("12345"),
      Ticker("VOD"),
      Some(ClassCode("LSE")),
      "VODAFONE GROUP PLC",
      "STK",
      Currency(currency),
      updatedAt
    )

  "IbkrInstruments" >> {
    "of" >> {
      "describes an instrument by the symbol, exchange, description, asset category and currency of its entry" >> {
        IbkrInstruments.of(statement(List(security("12345"))), updatedAt) must_== List(vodafone())
      }

      "takes the currency from a trade of the instrument when the entry has none" >> {
        val entry = security("12345", currency = None)
        val bought = trade("12345", "currency" -> "GBP")

        IbkrInstruments.of(statement(List(entry), List(bought)), updatedAt) must_==
          List(vodafone())
      }

      "keeps what the entry says over what a trade says" >> {
        val bought = trade("12345", "currency" -> "USD", "symbol" -> "OTHER")

        IbkrInstruments.of(statement(List(security("12345")), List(bought)), updatedAt) must_==
          List(vodafone())
      }

      "describes an instrument with no entry from a trade of it" >> {
        val bought = trade(
          "12345",
          "symbol" -> "VOD",
          "listingExchange" -> "LSE",
          "description" -> "VODAFONE GROUP PLC",
          "currency" -> "GBP"
        )

        IbkrInstruments.of(statement(Nil, List(bought)), updatedAt) must_== List(vodafone())
      }

      "describes an instrument once however many trades and entries there are" >> {
        val trades = List(
          trade("12345", "symbol" -> "VOD", "currency" -> "GBP"),
          trade("12345", "symbol" -> "VOD", "currency" -> "GBP")
        )

        IbkrInstruments
          .of(statement(List(security("12345"), security("12345")), trades), updatedAt)
          .map(_.instrument) must_== List(IbkrFakes.instrumentKey("12345"))
      }

      "names the instrument by its symbol when nothing describes it" >> {
        val entry = security("12345", description = None)

        IbkrInstruments.of(statement(List(entry)), updatedAt).map(_.name) must_== List("VOD")
      }

      "leaves out the pair of a currency conversion, which is no instrument" >> {
        val conversion =
          trade("12087792", "assetCategory" -> "CASH", "symbol" -> "EUR.USD", "currency" -> "USD")

        IbkrInstruments.of(statement(Nil, List(conversion)), updatedAt) must beEmpty
      }

      "leaves out an instrument for which no currency is known" >> {
        val entry = security("12345", currency = None)

        IbkrInstruments.of(statement(List(entry)), updatedAt) must beEmpty
      }

      "leaves out an instrument for which no asset category is known" >> {
        val entry = security("12345", assetCategory = None)

        IbkrInstruments.of(statement(List(entry)), updatedAt) must beEmpty
      }
    }
  }
}
