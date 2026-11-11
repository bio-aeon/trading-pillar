package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.{Broker, InstrumentKey}
import st.btu.trading.pillar.models.ibkr.IbkrAttributes.Category
import st.btu.trading.pillar.models.ibkr.{IbkrOpenPosition, IbkrOpenPositions}
import st.btu.trading.pillar.models.market.Ticker

// The comparison of the positions recorded for an IB account with the Open Positions IB reports.
// Pure: `IbkrReconciler` reads the recorded positions and acts on the differences.
private[services] object IbkrReconciliation {

  private val Zero = BigDecimal(0)

  /** An instrument whose recorded position is not the one IB reports.
    *
    * @param instrument
    *   the contract at IB
    * @param symbol
    *   IB's symbol for the instrument when the statement gives one, else a symbol the caller found
    * @param recorded
    *   the position the account's operations leave; zero when they leave none
    * @param reported
    *   the position IB reports as open; zero when it reports none
    */
  final case class Difference(
    instrument: InstrumentKey,
    symbol: Option[Ticker],
    recorded: BigDecimal,
    reported: BigDecimal
  )

  /** The instruments whose position in `holdings` is not the stock position in `reported`, ordered
    * by contract. A contract on one side only counts as zero on the other, positions of another
    * asset category are not compared, and several rows of one contract add up.
    */
  def differences(
    holdings: Map[InstrumentKey, BigDecimal],
    reported: IbkrOpenPositions
  ): List[Difference] = {
    val stocks = reported.positions.filter(_.assetCategory.contains(Category.Stock))
    val quantities = stocks.groupMapReduce(instrumentOf)(_.quantity)(_ + _)
    val symbols = stocks.flatMap(position => position.symbol.map(instrumentOf(position) -> _)).toMap
    (holdings.keySet ++ quantities.keySet).toList.sorted.flatMap { instrument =>
      val recorded = holdings.getOrElse(instrument, Zero)
      val reportedQuantity = quantities.getOrElse(instrument, Zero)
      Option.when(recorded != reportedQuantity)(
        Difference(instrument, symbols.get(instrument), recorded, reportedQuantity)
      )
    }
  }

  private def instrumentOf(position: IbkrOpenPosition): InstrumentKey =
    InstrumentKey(Broker.Ibkr, position.conid)
}
