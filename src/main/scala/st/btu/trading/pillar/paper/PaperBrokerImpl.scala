package st.btu.trading.pillar.paper

import cats.MonadThrow
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.alerts.StrategyBinding
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Quote, Ticker}
import st.btu.trading.pillar.storages.PaperStorage
import st.btu.trading.pillar.strategy.indicators.Indicators.divide
import st.btu.trading.pillar.strategy.{Side, Sizing, SizingResolution, StrategySignal}

import java.time.ZonedDateTime

// Long only, whole shares - resolveBuyQuantity/resolveSellQuantity (shared with BacktestEngine)
// already enforce both: a buy never exceeds what cash affords, a sell never exceeds what is owned.
// Every reject path still produces a PaperOrder (quantity 0, status Rejected) rather than raising,
// since "the strategy's order was turned down" is an expected outcome, not a failure of this
// component - see docs/paper-trading.md for the four reject reasons.
final class PaperBrokerImpl[F[_]] private (storage: PaperStorage[F], config: PaperBrokerConfig)(
  implicit F: MonadThrow[F]
) extends PaperBroker[F] {

  def execute(
    binding: StrategyBinding,
    signal: StrategySignal,
    quote: Quote,
    now: ZonedDateTime
  ): F[PaperOrder] =
    signal match {
      case StrategySignal.Buy(ticker, sizing, reason) =>
        executeBuy(binding, ticker, sizing, reason, quote, now)
      case StrategySignal.Sell(ticker, sizing, reason) =>
        executeSell(binding, ticker, sizing, reason, quote, now)
      case StrategySignal.Hold(_) =>
        F.raiseError(
          new IllegalArgumentException(
            "PaperBroker.execute does not accept Hold signals - callers must filter LiveEvaluation.signals to Buy/Sell"
          )
        )
    }

  private def executeBuy(
    binding: StrategyBinding,
    ticker: Ticker,
    sizing: Sizing,
    reason: String,
    quote: Quote,
    now: ZonedDateTime
  ): F[PaperOrder] = {
    val blank = blankOrder(binding, ticker, Side.Buy, now)
    storage.account(binding.accountId).flatMap {
      case None =>
        reject(blank, s"No paper account for account ${binding.accountId.value}")
      case Some(account) =>
        storage.positions(binding.accountId).flatMap { positions =>
          val existing = positions.find(_.ticker == ticker)
          val currentQty = existing.map(_.quantity).getOrElse(0L)
          val fillPrice = SizingResolution.buyFillPrice(quote.price, config.slippageBps)
          val equity = account.cash + BigDecimal(currentQty) * quote.price
          val maxQty =
            SizingResolution.maxAffordableQty(account.cash, fillPrice, config.commissionRate)
          val qty = SizingResolution
            .resolveBuyQuantity(sizing, account.cash, equity, fillPrice, config.commissionRate)
          // maxQty <= 0 means cash can't cover even one share, regardless of what the sizing asked
          // for - a more specific diagnosis than "the sizing resolved to zero" (e.g. an explicit
          // Sizing.Quantity(0), or a FractionOfEquity too small to round up to one share).
          if (maxQty <= 0L) reject(blank, s"Insufficient cash to buy ${ticker.value} ($reason)")
          else if (qty <= 0L) reject(blank, s"Buy resolved to zero quantity ($reason)")
          else fillBuy(binding, ticker, qty, fillPrice, account, existing, reason, now)
        }
    }
  }

  private def fillBuy(
    binding: StrategyBinding,
    ticker: Ticker,
    qty: Long,
    fillPrice: BigDecimal,
    account: PaperAccount,
    existing: Option[PaperPosition],
    reason: String,
    now: ZonedDateTime
  ): F[PaperOrder] = {
    val notional = BigDecimal(qty) * fillPrice
    val commission = config.commissionRate * notional
    val newCash = account.cash - notional - commission
    val newPosition = applyBuy(existing, binding.accountId, ticker, qty, fillPrice, now)
    val order = filledOrder(binding, ticker, Side.Buy, qty, fillPrice, commission, reason, now)
    for {
      _ <- storage.updateCash(binding.accountId, newCash)
      _ <- storage.upsertPosition(newPosition)
      id <- storage.insertOrder(order)
    } yield order.copy(id = Some(id))
  }

  private def applyBuy(
    existing: Option[PaperPosition],
    accountId: AccountId,
    ticker: Ticker,
    qty: Long,
    fillPrice: BigDecimal,
    now: ZonedDateTime
  ): PaperPosition =
    existing match {
      case Some(p) =>
        val totalQty = p.quantity + qty
        val newAvgCost = divide(
          BigDecimal(p.quantity) * p.averageCost + BigDecimal(qty) * fillPrice,
          BigDecimal(totalQty)
        )
        p.copy(quantity = totalQty, averageCost = newAvgCost, updatedAt = now)
      case None =>
        PaperPosition(accountId, ticker, qty, fillPrice, now)
    }

  private def executeSell(
    binding: StrategyBinding,
    ticker: Ticker,
    sizing: Sizing,
    reason: String,
    quote: Quote,
    now: ZonedDateTime
  ): F[PaperOrder] = {
    val blank = blankOrder(binding, ticker, Side.Sell, now)
    storage.account(binding.accountId).flatMap {
      case None =>
        reject(blank, s"No paper account for account ${binding.accountId.value}")
      case Some(account) =>
        storage.positions(binding.accountId).flatMap { positions =>
          positions.find(_.ticker == ticker) match {
            case None => reject(blank, s"Sell without a position ($reason)")
            case Some(position) =>
              val fillPrice = SizingResolution.sellFillPrice(quote.price, config.slippageBps)
              val equity = account.cash + BigDecimal(position.quantity) * quote.price
              val qty =
                SizingResolution.resolveSellQuantity(sizing, position.quantity, equity, fillPrice)
              if (qty <= 0L) reject(blank, s"Sell resolved to zero quantity ($reason)")
              else fillSell(binding, ticker, qty, fillPrice, account, position, reason, now)
          }
        }
    }
  }

  private def fillSell(
    binding: StrategyBinding,
    ticker: Ticker,
    qty: Long,
    fillPrice: BigDecimal,
    account: PaperAccount,
    position: PaperPosition,
    reason: String,
    now: ZonedDateTime
  ): F[PaperOrder] = {
    val notional = BigDecimal(qty) * fillPrice
    val commission = config.commissionRate * notional
    val newCash = account.cash + notional - commission
    val remaining = position.quantity - qty
    val order = filledOrder(binding, ticker, Side.Sell, qty, fillPrice, commission, reason, now)
    for {
      _ <- storage.updateCash(binding.accountId, newCash)
      _ <-
        if (remaining <= 0L) storage.deletePosition(binding.accountId, ticker)
        else storage.upsertPosition(position.copy(quantity = remaining, updatedAt = now))
      id <- storage.insertOrder(order)
    } yield order.copy(id = Some(id))
  }

  private def blankOrder(
    binding: StrategyBinding,
    ticker: Ticker,
    side: Side,
    now: ZonedDateTime
  ): PaperOrder =
    PaperOrder(
      id = None,
      accountId = binding.accountId,
      bindingId = Some(binding.id),
      ticker = ticker,
      side = side,
      quantity = 0L,
      requestedAt = now,
      filledAt = None,
      fillPrice = None,
      commission = None,
      status = PaperOrderStatus.Rejected,
      reason = None
    )

  private def filledOrder(
    binding: StrategyBinding,
    ticker: Ticker,
    side: Side,
    qty: Long,
    fillPrice: BigDecimal,
    commission: BigDecimal,
    reason: String,
    now: ZonedDateTime
  ): PaperOrder =
    blankOrder(binding, ticker, side, now).copy(
      quantity = qty,
      filledAt = Some(now),
      fillPrice = Some(fillPrice),
      commission = Some(commission),
      status = PaperOrderStatus.Filled,
      reason = Some(reason)
    )

  private def reject(blank: PaperOrder, reason: String): F[PaperOrder] = {
    val order = blank.copy(reason = Some(reason))
    storage.insertOrder(order).map(id => order.copy(id = Some(id)))
  }
}

object PaperBrokerImpl {

  /** Wires a [[PaperBroker]] backed by `storage`; construction itself has no effect. */
  def create[F[_]: MonadThrow](
    storage: PaperStorage[F],
    config: PaperBrokerConfig
  ): PaperBroker[F] =
    new PaperBrokerImpl[F](storage, config)
}
