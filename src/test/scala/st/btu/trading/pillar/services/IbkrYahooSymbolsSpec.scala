package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.YahooSymbol.value
import st.btu.trading.pillar.models.market.{ClassCode, Ticker}

class IbkrYahooSymbolsSpec extends Specification {

  private def yahooSymbol(symbol: String, exchange: String): Option[String] =
    IbkrYahooSymbols.of(Ticker(symbol), ClassCode(exchange)).map(_.value)

  "IbkrYahooSymbols" >> {
    "of" >> {
      "adds the suffix of the exchange the instrument is listed on" >> {
        yahooSymbol("VOD", "LSE") must beSome("VOD.L")
      }

      "adds nothing for a US exchange" >> {
        yahooSymbol("AAPL", "NASDAQ") must beSome("AAPL")
      }

      "turns a space before a share class into a hyphen" >> {
        yahooSymbol("BRK B", "NYSE") must beSome("BRK-B")
      }

      "turns a dot before a share class into a hyphen, ahead of the suffix" >> {
        yahooSymbol("BT.A", "LSE") must beSome("BT-A.L")
      }

      "drops a dot at the end of the symbol, since the suffix brings its own" >> {
        yahooSymbol("BP.", "LSE") must beSome("BP.L")
      }

      "is none for an exchange without a rule" >> {
        yahooSymbol("AAPL", "SMART") must beNone
      }
    }
  }
}
