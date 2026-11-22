package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.binance.BinanceAsset
import st.btu.trading.pillar.models.domain.OperationRef

// Something about a record that the translation could not do as it would with a price, and what it
// did instead. The sync raises each once as a system alert.
private[services] final case class BinanceNote(record: OperationRef, reason: BinanceNote.Reason)

private[services] object BinanceNote {

  enum Reason {

    /** No price values a trade or a Convert between two coins, so its legs are conversions. */
    case LegsUnvalued

    /** No price values a commission paid in a coin, so the coin leaves at its cost. */
    case CommissionUnvalued(asset: BinanceAsset, quantity: BigDecimal)

    /** No price values the network fee of a withdrawal, so the coin leaves at its cost. */
    case NetworkFeeUnvalued(asset: BinanceAsset, quantity: BigDecimal)

    /** No price values a coin credited as a distribution, so it is received at no cost and counts
      * for no income.
      */
    case DistributionUnvalued(asset: BinanceAsset, quantity: BigDecimal)
  }

  def describe(note: BinanceNote): String = {
    val ref = note.record.value
    note.reason match {
      case Reason.LegsUnvalued =>
        s"$ref: no price values the two coins, so its legs are a conversion that carries their cost"
      case Reason.CommissionUnvalued(asset, quantity) =>
        s"$ref: no price values the commission of ${quantity.bigDecimal.toPlainString} ${asset.value}, " +
          "which leaves the account at its cost"
      case Reason.NetworkFeeUnvalued(asset, quantity) =>
        s"$ref: no price values the network fee of ${quantity.bigDecimal.toPlainString} " +
          s"${asset.value}, which leaves the account at its cost"
      case Reason.DistributionUnvalued(asset, quantity) =>
        s"$ref: no price values the distribution of ${quantity.bigDecimal.toPlainString} " +
          s"${asset.value}, which is received at no cost and counts for no income"
    }
  }
}
