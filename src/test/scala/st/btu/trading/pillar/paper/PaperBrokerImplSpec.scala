package st.btu.trading.pillar.paper

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.market.{Quote, Source, Ticker}
import st.btu.trading.pillar.strategy.{Side, Sizing, StrategySignal}
import st.btu.trading.pillar.testkit.{AlertFakes, PaperFakes}

import java.time.{ZoneOffset, ZonedDateTime}

class PaperBrokerImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val usd = Currency("USD")
  private val ticker = Ticker("AAPL")
  private val now = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)
  private val binding = AlertFakes.strategyBinding(accountId = accountId, ticker = ticker)

  private def quote(price: BigDecimal): Quote = Quote(ticker, price, usd, now, Source.Yahoo)

  private def buildBroker(
    accounts: List[PaperAccount] = List.empty,
    positions: List[PaperPosition] = List.empty,
    config: PaperBrokerConfig = PaperBrokerConfig(0, 0)
  ): IO[(PaperBroker[IO], PaperFakes.FakePaperStorage)] =
    PaperFakes
      .paperStorage(accounts, positions)
      .map(s => (PaperBrokerImpl.create[IO](s, config), s))

  "PaperBrokerImpl" >> {
    "execute" >> {

      "fills a buy at the quote price adjusted by slippage, with commission on the notional" >> {
        for {
          bs <- buildBroker(
            accounts = List(PaperAccount(accountId, 100000, usd)),
            config = PaperBrokerConfig(commissionRate = BigDecimal("0.001"), slippageBps = 50)
          )
          (broker, storage) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Buy(ticker, Sizing.Quantity(10), "test buy"),
            quote(100),
            now
          )
          accounts <- storage.accounts
          positions <- storage.allPositions
        } yield {
          order.status must_== PaperOrderStatus.Filled
          order.side must_== Side.Buy
          order.quantity must_== 10L
          order.fillPrice must beSome(BigDecimal("100.5"))
          order.commission must beSome(BigDecimal("1.005"))
          order.id must beSome
          order.reason must beSome("test buy")
          accounts(accountId).cash must_== BigDecimal("98993.995")
          positions must_== List(PaperPosition(accountId, ticker, 10L, BigDecimal("100.5"), now))
        }
      }

      "fills a sell at the quote price adjusted by slippage (down), with commission on the notional" >> {
        val existing = PaperPosition(accountId, ticker, 20L, 90, now.minusDays(1))
        for {
          bs <- buildBroker(
            accounts = List(PaperAccount(accountId, 1000, usd)),
            positions = List(existing),
            config = PaperBrokerConfig(commissionRate = BigDecimal("0.001"), slippageBps = 50)
          )
          (broker, storage) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Sell(ticker, Sizing.Quantity(5), "test sell"),
            quote(100),
            now
          )
          accounts <- storage.accounts
          positions <- storage.allPositions
        } yield {
          order.status must_== PaperOrderStatus.Filled
          order.side must_== Side.Sell
          order.quantity must_== 5L
          order.fillPrice must beSome(BigDecimal("99.5"))
          order.commission must beSome(BigDecimal("0.4975"))
          accounts(accountId).cash must_== BigDecimal("1497.0025")
          // Partial sell: position survives with the remaining quantity and a bumped updatedAt,
          // average cost unchanged.
          positions must_== List(existing.copy(quantity = 15L, updatedAt = now))
        }
      }

      "updates the average cost on a second buy (weighted by quantity)" >> {
        val existing = PaperPosition(accountId, ticker, 10L, 100, now.minusDays(1))
        for {
          bs <- buildBroker(
            accounts = List(PaperAccount(accountId, 100000, usd)),
            positions = List(existing)
          )
          (broker, storage) = bs
          _ <- broker.execute(
            binding,
            StrategySignal.Buy(ticker, Sizing.Quantity(10), "add"),
            quote(110),
            now
          )
          positions <- storage.allPositions
        } yield positions must_== List(PaperPosition(accountId, ticker, 20L, 105, now))
      }

      "deletes the position once a sell brings its quantity to zero" >> {
        val existing = PaperPosition(accountId, ticker, 5L, 100, now.minusDays(1))
        for {
          bs <- buildBroker(
            accounts = List(PaperAccount(accountId, 1000, usd)),
            positions = List(existing)
          )
          (broker, storage) = bs
          _ <- broker.execute(
            binding,
            StrategySignal.Sell(ticker, Sizing.AllIn, "close out"),
            quote(50),
            now
          )
          positions <- storage.allPositions
        } yield positions must beEmpty
      }

      "rejects a buy when the paper account does not exist" >> {
        for {
          bs <- buildBroker(accounts = List.empty)
          (broker, _) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Buy(ticker, Sizing.AllIn, "no account"),
            quote(100),
            now
          )
        } yield {
          order.status must_== PaperOrderStatus.Rejected
          order.quantity must_== 0L
          order.reason must beSome.like { case r => r must contain("No paper account") }
        }
      }

      "rejects a sell when the paper account does not exist" >> {
        for {
          bs <- buildBroker(accounts = List.empty)
          (broker, _) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Sell(ticker, Sizing.AllIn, "no account"),
            quote(100),
            now
          )
        } yield order.status must_== PaperOrderStatus.Rejected
      }

      "rejects a buy whose sizing resolves to zero quantity even though cash is ample" >> {
        for {
          bs <- buildBroker(accounts = List(PaperAccount(accountId, 100000, usd)))
          (broker, _) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Buy(ticker, Sizing.Quantity(0), "zero"),
            quote(100),
            now
          )
        } yield {
          order.status must_== PaperOrderStatus.Rejected
          order.reason must beSome.like { case r => r must contain("zero quantity") }
        }
      }

      "rejects a sell whose sizing resolves to zero quantity even though a position exists" >> {
        val existing = PaperPosition(accountId, ticker, 10L, 100, now.minusDays(1))
        for {
          bs <- buildBroker(
            accounts = List(PaperAccount(accountId, 1000, usd)),
            positions = List(existing)
          )
          (broker, _) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Sell(ticker, Sizing.Quantity(0), "zero"),
            quote(100),
            now
          )
        } yield {
          order.status must_== PaperOrderStatus.Rejected
          order.reason must beSome.like { case r => r must contain("zero quantity") }
        }
      }

      "rejects a buy when cash can't cover even one share, distinctly from a zero-quantity sizing" >> {
        for {
          bs <- buildBroker(accounts = List(PaperAccount(accountId, 1, usd)))
          (broker, _) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Buy(ticker, Sizing.Quantity(5), "too poor"),
            quote(100),
            now
          )
        } yield {
          order.status must_== PaperOrderStatus.Rejected
          order.reason must beSome.like { case r => r must contain("Insufficient cash") }
        }
      }

      "rejects a sell when there is no position for the ticker" >> {
        for {
          bs <- buildBroker(
            accounts = List(PaperAccount(accountId, 1000, usd)),
            positions = List.empty
          )
          (broker, _) = bs
          order <- broker.execute(
            binding,
            StrategySignal.Sell(ticker, Sizing.AllIn, "nothing to sell"),
            quote(100),
            now
          )
        } yield {
          order.status must_== PaperOrderStatus.Rejected
          order.reason must beSome.like { case r => r must contain("without a position") }
        }
      }

      "raises rather than silently accepting a hold signal, since callers are expected to filter those out beforehand" >> {
        for {
          bs <- buildBroker(accounts = List(PaperAccount(accountId, 1000, usd)))
          (broker, _) = bs
          result <- broker.execute(binding, StrategySignal.Hold("n/a"), quote(100), now).attempt
        } yield result must beLeft
      }
    }
  }
}
