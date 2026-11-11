package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.Broker
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, Source, SourceSetting}

// The source setting that the pricing rule of an instrument's broker gives its ticker. The held
// instruments service writes it for a held ticker that has no setting.
private[services] object HeldInstrumentPricing {

  /** What a broker's pricing rule says about one instrument's ticker. */
  enum Outcome {

    /** The ticker is priced from this source. */
    case Setting(setting: SourceSetting)

    /** The source the rule would choose is not one the router serves, so the ticker keeps the
      * default source.
      */
    case NoSetting

    /** Interactive Brokers lists the instrument on an exchange, or on none, with no Yahoo rule. */
    case NoExchangeRule(exchange: Option[ClassCode])
  }

  // `serves` says which sources the router has an adapter for: a setting it cannot serve would
  // only make every sync of the ticker fail.
  def of(instrument: BrokerInstrument, serves: Source => Boolean): Outcome =
    instrument.instrument.broker match {
      case Broker.Tinkoff =>
        if (serves(Source.Tinkoff)) Outcome.Setting(SourceSetting.Tinkoff(instrument.classCode))
        else Outcome.NoSetting
      case Broker.Ibkr =>
        if (serves(Source.Yahoo)) yahooSetting(instrument) else Outcome.NoSetting
    }

  private def yahooSetting(instrument: BrokerInstrument): Outcome =
    instrument.classCode.flatMap(IbkrYahooSymbols.of(instrument.ticker, _)) match {
      case Some(symbol) =>
        // A symbol that is the ticker itself is left out: a ticker with a Yahoo setting is priced
        // there even when the default source is another.
        val named = Option.when(symbol.value != instrument.ticker.value)(symbol)
        Outcome.Setting(SourceSetting.Yahoo(named))
      case None => Outcome.NoExchangeRule(instrument.classCode)
    }
}
