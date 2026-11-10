package st.btu.trading.pillar.models.ibkr

import st.btu.trading.pillar.models.domain.{AccountId, BrokerInstrumentId, Currency}
import st.btu.trading.pillar.models.market.{ClassCode, Ticker}

import java.time.LocalDate

/** The statement of one IB account, as read from the response of an Activity Flex Query.
  *
  * @param fromDate
  *   the first report date the statement covers
  * @param toDate
  *   the last report date the statement covers
  * @param rows
  *   the rows worth keeping, in the order the statement lists them
  * @param securities
  *   the instruments of the statement's Financial Instrument Information section
  */
final case class IbkrStatement(
  ibkrAccountId: IbkrAccountId,
  fromDate: LocalDate,
  toDate: LocalDate,
  rows: List[IbkrReportedRow],
  securities: List[IbkrSecurityInfo]
)

/** A statement row as IB reported it, before it is stored under an account of the bot. */
final case class IbkrReportedRow(
  section: IbkrSection,
  transactionId: IbkrTransactionId,
  reportDate: LocalDate,
  attributes: Map[String, String]
) {

  /** This row, stored under `accountId`. */
  def attributedTo(accountId: AccountId): IbkrStatementRow =
    IbkrStatementRow(accountId, section, transactionId, reportDate, attributes)
}

/** An instrument of a statement's Financial Instrument Information section.
  *
  * @param conid
  *   IB's contract id, the id IB knows the instrument by
  * @param symbol
  *   IB's symbol for the instrument
  * @param listingExchange
  *   the exchange the instrument is listed on, when the statement says
  * @param assetCategory
  *   IB's asset class of the instrument, such as `STK`
  * @param currency
  *   the currency the instrument trades in, when the statement says
  */
final case class IbkrSecurityInfo(
  conid: BrokerInstrumentId,
  symbol: Ticker,
  listingExchange: Option[ClassCode],
  isin: Option[String],
  description: Option[String],
  assetCategory: Option[String],
  currency: Option[Currency]
)
