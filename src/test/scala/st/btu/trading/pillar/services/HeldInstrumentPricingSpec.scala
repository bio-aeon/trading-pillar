package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.services.HeldInstrumentPricing.Outcome

import java.time.{ZoneOffset, ZonedDateTime}

class HeldInstrumentPricingSpec extends Specification {

  // The sources a router serves, as the rule asks for them.
  private val servesTinkoff: Source => Boolean = Set(Source.Tinkoff)
  private val servesYahoo: Source => Boolean = Set(Source.Yahoo)
  private val servesNothing: Source => Boolean = Set.empty

  private def instrument(
    broker: Broker,
    ticker: String,
    exchange: Option[String]
  ): BrokerInstrument =
    BrokerInstrument(
      InstrumentKey(broker, BrokerInstrumentId("1")),
      Ticker(ticker),
      exchange.map(ClassCode(_)),
      ticker,
      "share",
      Currency("USD"),
      ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    )

  private val sber = instrument(Broker.Tinkoff, "SBER", Some("TQBR"))

  "HeldInstrumentPricing" >> {
    "of" >> {
      "Tinkoff" >> {
        "is Tinkoff on the instrument's own board while the router serves Tinkoff" >> {
          HeldInstrumentPricing.of(sber, servesTinkoff) must_==
            Outcome.Setting(SourceSetting.Tinkoff(Some(ClassCode("TQBR"))))
        }

        "is no setting while the router does not serve Tinkoff, though it serves Yahoo" >> {
          HeldInstrumentPricing.of(sber, servesYahoo) must_== Outcome.NoSetting
        }
      }

      "Interactive Brokers" >> {
        "is Yahoo under the symbol the exchange rule gives while the router serves Yahoo" >> {
          HeldInstrumentPricing.of(instrument(Broker.Ibkr, "VOD", Some("LSE")), servesYahoo) must_==
            Outcome.Setting(SourceSetting.Yahoo(Some(YahooSymbol("VOD.L"))))
        }

        "is Yahoo as given when the symbol is the ticker itself" >> {
          HeldInstrumentPricing.of(
            instrument(Broker.Ibkr, "AAPL", Some("NASDAQ")),
            servesYahoo
          ) must_== Outcome.Setting(SourceSetting.Yahoo(None))
        }

        "is no setting while the router does not serve Yahoo, though it serves Tinkoff" >> {
          HeldInstrumentPricing.of(
            instrument(Broker.Ibkr, "VOD", Some("LSE")),
            servesTinkoff
          ) must_== Outcome.NoSetting
        }

        "is no setting, with no exchange rule asked for, while the router serves nothing" >> {
          HeldInstrumentPricing.of(
            instrument(Broker.Ibkr, "AAPL", Some("SMART")),
            servesNothing
          ) must_== Outcome.NoSetting
        }

        "has no exchange rule for an exchange the rule does not know" >> {
          HeldInstrumentPricing.of(
            instrument(Broker.Ibkr, "AAPL", Some("SMART")),
            servesYahoo
          ) must_== Outcome.NoExchangeRule(Some(ClassCode("SMART")))
        }

        "has no exchange rule for an instrument that names no exchange" >> {
          HeldInstrumentPricing.of(instrument(Broker.Ibkr, "AAPL", None), servesYahoo) must_==
            Outcome.NoExchangeRule(None)
        }
      }
    }
  }
}
