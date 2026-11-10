package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.ibkr.IbkrAttributes.{Category, Name}
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, Ticker}

import java.time.ZonedDateTime

// The instruments a statement describes, as the broker instruments the directory answers from.
// Each comes from the statement's Financial Instrument Information section; whatever that lacks
// for an instrument, or all of it when the instrument has no entry, is taken from a trade of the
// instrument. An instrument still lacking a symbol, an asset category or a currency is left out.
private[services] object IbkrInstruments {

  def of(statement: IbkrStatement, updatedAt: ZonedDateTime): List[BrokerInstrument] = {
    val declared: Map[BrokerInstrumentId, IbkrSecurityInfo] =
      statement.securities.map(security => security.conid -> security).toMap

    // The attributes of the first trade of each instrument, other than a currency conversion, which
    // has no instrument to describe.
    val traded: Map[BrokerInstrumentId, Map[String, String]] =
      statement.rows
        .filter(_.section == IbkrSection.Trade)
        .filterNot(row => attributeOf(row.attributes, Name.AssetCategory).contains(Category.Cash))
        .flatMap(row => attributeOf(row.attributes, Name.Conid).map(_ -> row.attributes))
        .groupMap { case (conid, _) => BrokerInstrumentId(conid) } { case (_, attributes) =>
          attributes
        }
        .view
        .mapValues(_.head)
        .toMap

    (statement.securities.map(_.conid) ++ traded.keys).distinct.flatMap { conid =>
      instrumentOf(conid, declared.get(conid), traded.getOrElse(conid, Map.empty), updatedAt)
    }
  }

  private def attributeOf(attributes: Map[String, String], name: String): Option[String] =
    IbkrAttributes.value(attributes, name)

  private def instrumentOf(
    conid: BrokerInstrumentId,
    declared: Option[IbkrSecurityInfo],
    trade: Map[String, String],
    updatedAt: ZonedDateTime
  ): Option[BrokerInstrument] = {
    def fromTrade(name: String): Option[String] = attributeOf(trade, name)

    for {
      ticker <- declared.map(_.symbol).orElse(fromTrade(Name.Symbol).map(Ticker(_)))
      instrumentType <- declared.flatMap(_.assetCategory).orElse(fromTrade(Name.AssetCategory))
      currency <- declared.flatMap(_.currency).orElse(fromTrade(Name.Currency).map(Currency(_)))
    } yield BrokerInstrument(
      InstrumentKey(Broker.Ibkr, conid),
      ticker,
      declared.flatMap(_.listingExchange).orElse(fromTrade(Name.ListingExchange).map(ClassCode(_))),
      declared.flatMap(_.description).orElse(fromTrade(Name.Description)).getOrElse(ticker.value),
      instrumentType,
      currency,
      updatedAt
    )
  }
}
