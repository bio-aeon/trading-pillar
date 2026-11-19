package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import io.circe.JsonObject
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.services.BinancePrices.PriceBook
import st.btu.trading.pillar.services.BinanceTranslation.{Context, Result}
import st.btu.trading.pillar.testkit.BinanceFakes.*

import java.time.Instant

class BinanceTranslationSpec extends Specification {

  private val accountId = AccountId(1)

  private val cash = Set("USDT", "USDC", "FDUSD", "BUSD").map(asset)
  private val quotes = NonEmptyList.of(asset("USDC"), asset("USDT"))

  private val listedPairs = List(
    pairOf("BTC", "USDT"),
    pairOf("BTC", "USDC"),
    pairOf("ETH", "BTC"),
    pairOf("ETH", "USDT"),
    pairOf("BNB", "USDT"),
    pairOf("USDC", "USDT"),
    pairOf("USDT", "TRY")
  )

  private val context = Context(cash, quotes, listedPairs.map(p => p.symbol -> p).toMap)

  // When every trade of these specs was made, and the start of its minute.
  private val tradeTime = "2026-03-10T12:34:56.789Z"
  private val tradeMinute = "2026-03-10T12:34:00Z"

  // The closes of the bars that hold the trades.
  private def book(closes: (String, String)*): PriceBook =
    PriceBook(found = closes.map { case (pairName, close) =>
      minute(pairName, tradeMinute) -> BigDecimal(close)
    }.toMap)

  private def absent(pairNames: String*): PriceBook =
    PriceBook(absent = pairNames.map(minute(_, tradeMinute)).toSet)

  private def translate(
    source: BinanceSource,
    attributes: JsonObject,
    prices: PriceBook = PriceBook()
  ): Result =
    BinanceTranslation
      .translate(record(accountId, source, attributes), context, prices)
      .fold(error => throw new AssertionError(error.getMessage), identity)

  private def operations(result: Result): List[Shown] = result.operations.map(shown)

  private def tradeOf(
    id: Long,
    pairName: String,
    price: String,
    qty: String,
    quoteQty: String,
    isBuyer: Boolean,
    commission: String = "0",
    commissionAsset: String = "BNB"
  ) = trade(pairName, id, tradeTime, price, qty, quoteQty, isBuyer, commission, commissionAsset)

  private def states(result: Result): Set[OperationState] = result.operations.map(_.state).toSet

  "BinanceTranslation" >> {
    "translate" >> {
      "a trade against cash" >> {
        "a buy of the coin pays the quote and gets the quantity, with its commission in the coin coming off it" >> {
          val result = translate(
            BinanceSource.Trade,
            tradeOf(101, "BTCUSDT", "50000.00", "0.10", "5000.00", true, "0.0001", "BTC")
          )
          operations(result) must_== List(
            (
              "trade/BTCUSDT/101",
              "buy",
              Some("BTC"),
              BigDecimal("0.0999"),
              Some(BigDecimal("50000.00")),
              BigDecimal("-5000.00"),
              "USDT",
              "Buy"
            )
          )
        }

        "a sale of the coin gives the quantity and gets the quote, with its commission in the quote as a fee naming the coin" >> {
          val result = translate(
            BinanceSource.Trade,
            tradeOf(102, "BTCUSDT", "40000", "0.5", "20000", false, "20", "USDT")
          )
          operations(result) must_== List(
            (
              "trade/BTCUSDT/102",
              "sell",
              Some("BTC"),
              BigDecimal("-0.5"),
              Some(BigDecimal(40000)),
              BigDecimal(20000),
              "USDT",
              "Sell"
            ),
            (
              "trade/BTCUSDT/102/fee",
              "fee",
              Some("BTC"),
              BigDecimal(0),
              None,
              BigDecimal(-20),
              "USDT",
              "Commission"
            )
          )
        }

        "is executed whatever it is" >> {
          states(
            translate(BinanceSource.Trade, tradeOf(1, "BTCUSDT", "1", "1", "1", true))
          ) must_== Set(OperationState.Executed)
        }

        "a commission in BNB is a sale of the BNB at its close in the currency of the trade, and a fee of the same value" >> {
          // The trade is in USDT. BNB has a bar against USDC too, which is the first price quote,
          // so the currency of the trade has to be what decides.
          val prices = book("BNBUSDT" -> "600", "BNBUSDC" -> "599")
          val result = translate(
            BinanceSource.Trade,
            tradeOf(103, "BTCUSDT", "50000", "0.2", "10000", true, "0.01", "BNB"),
            prices
          )
          operations(result) must_== List(
            (
              "trade/BTCUSDT/103",
              "buy",
              Some("BTC"),
              BigDecimal("0.2"),
              Some(BigDecimal(50000)),
              BigDecimal(-10000),
              "USDT",
              "Buy"
            ),
            (
              "trade/BTCUSDT/103/fee-sale",
              "sell",
              Some("BNB"),
              BigDecimal("-0.01"),
              Some(BigDecimal(600)),
              BigDecimal(6),
              "USDT",
              "Commission"
            ),
            (
              "trade/BTCUSDT/103/fee",
              "fee",
              Some("BTC"),
              BigDecimal(0),
              None,
              BigDecimal(-6),
              "USDT",
              "Commission"
            )
          )
        }

        "a commission in BNB falls back to the other price quotes when the currency of the trade has no bar" >> {
          val prices = book("BNBUSDC" -> "599").copy(absent = Set(minute("BNBUSDT", tradeMinute)))
          val result = translate(
            BinanceSource.Trade,
            tradeOf(103, "BTCUSDT", "50000", "0.2", "10000", true, "0.01", "BNB"),
            prices
          )
          operations(result).drop(1) must_== List(
            (
              "trade/BTCUSDT/103/fee-sale",
              "sell",
              Some("BNB"),
              BigDecimal("-0.01"),
              Some(BigDecimal(599)),
              BigDecimal("5.99"),
              "USDC",
              "Commission"
            ),
            (
              "trade/BTCUSDT/103/fee",
              "fee",
              Some("BTC"),
              BigDecimal(0),
              None,
              BigDecimal("-5.99"),
              "USDC",
              "Commission"
            )
          )
        }

        "a commission in BNB that no bar values leaves as units at their cost, and is noted" >> {
          val result = translate(
            BinanceSource.Trade,
            tradeOf(103, "BTCUSDT", "50000", "0.2", "10000", true, "0.01", "BNB"),
            absent("BNBUSDT", "BNBUSDC")
          )
          operations(result).drop(1) must_== List(
            (
              "trade/BTCUSDT/103/fee",
              "security_out",
              Some("BNB"),
              BigDecimal("-0.01"),
              None,
              BigDecimal(0),
              "USDC",
              "Commission"
            )
          )
          result.notes must_== List(
            BinanceNote(
              OperationRef("trade/BTCUSDT/103"),
              BinanceNote.Reason.CommissionUnvalued(asset("BNB"), BigDecimal("0.01"))
            )
          )
        }

        "the bars a commission in BNB might use are all examined, the currency of the trade first" >> {
          val result = translate(
            BinanceSource.Trade,
            tradeOf(103, "BTCUSDT", "50000", "0.2", "10000", true, "0.01", "BNB")
          )
          result.examined must_== List(
            minute("BNBUSDT", tradeMinute),
            minute("BNBUSDC", tradeMinute)
          )
          result.pending must beSome(minute("BNBUSDT", tradeMinute))
        }

        "a commission of zero is no operation" >> {
          operations(
            translate(
              BinanceSource.Trade,
              tradeOf(104, "BTCUSDT", "50000", "0.2", "10000", true, "0.00000000", "BNB")
            )
          ).map(_._1) must_== List("trade/BTCUSDT/104")
        }
      }

      "a trade between two coins" >> {
        // 1 ETH bought with 0.05 BTC
        val swap = tradeOf(104, "ETHBTC", "0.05", "1.0", "0.05", true)

        "sells the coin given up and buys the coin received at the value of the coin given up" >> {
          val result = translate(BinanceSource.Trade, swap, book("BTCUSDC" -> "60000"))
          operations(result) must_== List(
            (
              "trade/ETHBTC/104",
              "sell",
              Some("BTC"),
              BigDecimal("-0.05"),
              Some(BigDecimal(60000)),
              BigDecimal(3000),
              "USDC",
              "Buy"
            ),
            (
              "trade/ETHBTC/104/received",
              "buy",
              Some("ETH"),
              BigDecimal("1.0"),
              Some(BigDecimal(3000)),
              BigDecimal(-3000),
              "USDC",
              "Buy"
            )
          )
        }

        "the two cash amounts cancel" >> {
          val amounts =
            translate(BinanceSource.Trade, swap, book("BTCUSDC" -> "60000")).operations
              .map(_.amount)
          amounts.sum must_== BigDecimal(0)
        }

        "values it by the coin received when the coin given up has no bar" >> {
          val prices = absent("BTCUSDC", "BTCUSDT").copy(found =
            Map(minute("ETHUSDT", tradeMinute) -> BigDecimal(3100))
          )
          operations(translate(BinanceSource.Trade, swap, prices)) must_== List(
            (
              "trade/ETHBTC/104",
              "sell",
              Some("BTC"),
              BigDecimal("-0.05"),
              Some(BigDecimal(62000)),
              BigDecimal(3100),
              "USDT",
              "Buy"
            ),
            (
              "trade/ETHBTC/104/received",
              "buy",
              Some("ETH"),
              BigDecimal("1.0"),
              Some(BigDecimal(3100)),
              BigDecimal(-3100),
              "USDT",
              "Buy"
            )
          )
        }

        "prefers the first price quote for the coin given up over a later quote" >> {
          val prices = book("BTCUSDC" -> "60000", "BTCUSDT" -> "59990")
          operations(translate(BinanceSource.Trade, swap, prices)).map(_._7) must_==
            List("USDC", "USDC")
        }

        "a commission in the coin received comes off the quantity bought, so the coin costs more per unit" >> {
          // 2 ETH bought with 0.05 BTC, 0.5 ETH of it kept as the commission
          val charged = tradeOf(105, "ETHBTC", "0.025", "2.0", "0.05", true, "0.5", "ETH")
          operations(translate(BinanceSource.Trade, charged, book("BTCUSDC" -> "60000"))) must_==
            List(
              (
                "trade/ETHBTC/105",
                "sell",
                Some("BTC"),
                BigDecimal("-0.05"),
                Some(BigDecimal(60000)),
                BigDecimal(3000),
                "USDC",
                "Buy"
              ),
              (
                "trade/ETHBTC/105/received",
                "buy",
                Some("ETH"),
                BigDecimal("1.5"),
                Some(BigDecimal(2000)),
                BigDecimal(-3000),
                "USDC",
                "Buy"
              )
            )
        }

        "a commission in BNB is valued in the currency the trade was valued in" >> {
          // valued in USDT, since BTC has no bar against USDC
          val charged = tradeOf(106, "ETHBTC", "0.05", "1.0", "0.05", true, "0.01", "BNB")
          val prices = absent("BTCUSDC").copy(found =
            Map(
              minute("BTCUSDT", tradeMinute) -> BigDecimal(60000),
              minute("BNBUSDC", tradeMinute) -> BigDecimal(599),
              minute("BNBUSDT", tradeMinute) -> BigDecimal(600)
            )
          )
          val result = translate(BinanceSource.Trade, charged, prices)
          operations(result).map(_._7) must_== List("USDT", "USDT", "USDT", "USDT")
          operations(result)
            .find(_._1.endsWith("/fee"))
            .map(fee => (fee._3, fee._6)) must beSome((Some("ETH"), BigDecimal(-6)))
        }

        "when no bar values it, the legs are conversions of one group that carry the cost, and it is noted" >> {
          val result =
            translate(BinanceSource.Trade, swap, absent("BTCUSDC", "BTCUSDT", "ETHUSDC", "ETHUSDT"))
          operations(result) must_== List(
            (
              "trade/ETHBTC/104",
              "conversion",
              Some("BTC"),
              BigDecimal("-0.05"),
              None,
              BigDecimal(0),
              "USDC",
              "Buy"
            ),
            (
              "trade/ETHBTC/104/received",
              "conversion",
              Some("ETH"),
              BigDecimal("1.0"),
              None,
              BigDecimal(0),
              "USDC",
              "Buy"
            )
          )
          result.operations.map(_.group).distinct must_== List(
            Some(OperationGroup("trade/ETHBTC/104"))
          )
          result.notes must_== List(
            BinanceNote(OperationRef("trade/ETHBTC/104"), BinanceNote.Reason.LegsUnvalued)
          )
          result.pending must beNone
        }

        "asks for the bars one at a time, in order, until it can be valued" >> {
          val first = translate(BinanceSource.Trade, swap)
          first.pending must beSome(minute("BTCUSDC", tradeMinute))
          first.examined must_== List(
            minute("BTCUSDC", tradeMinute),
            minute("BTCUSDT", tradeMinute),
            minute("ETHUSDC", tradeMinute),
            minute("ETHUSDT", tradeMinute)
          )

          val second = translate(BinanceSource.Trade, swap, absent("BTCUSDC", "BTCUSDT"))
          second.pending must beSome(minute("ETHUSDC", tradeMinute))

          val last = translate(
            BinanceSource.Trade,
            swap,
            absent("BTCUSDC").copy(found = Map(minute("BTCUSDT", tradeMinute) -> BigDecimal(60000)))
          )
          last.pending must beNone
        }
      }

      "a trade between two cash assets" >> {
        "is two exchange operations, the cash given up leaving and the cash received coming in" >> {
          val result =
            translate(BinanceSource.Trade, tradeOf(110, "USDCUSDT", "0.9995", "100", "99.95", true))
          operations(result) must_== List(
            (
              "trade/USDCUSDT/110",
              "exchange",
              None,
              BigDecimal(0),
              None,
              BigDecimal("-99.95"),
              "USDT",
              "Buy"
            ),
            (
              "trade/USDCUSDT/110/received",
              "exchange",
              None,
              BigDecimal(0),
              None,
              BigDecimal(100),
              "USDC",
              "Buy"
            )
          )
        }

        "a commission in a cash asset is a fee that names no coin" >> {
          val result = translate(
            BinanceSource.Trade,
            tradeOf(110, "USDCUSDT", "0.9995", "100", "99.95", true, "0.1", "USDC")
          )
          operations(result).drop(2) must_== List(
            (
              "trade/USDCUSDT/110/fee",
              "fee",
              None,
              BigDecimal(0),
              None,
              BigDecimal("-0.1"),
              "USDC",
              "Commission"
            )
          )
        }
      }

      "a trade of a coin against a pair whose base is cash and whose quote is not" >> {
        "buying the base gives up the quote coin for cash, at the price derived from the amounts" >> {
          // 100 USDT bought with 4000 TRY
          val result =
            translate(BinanceSource.Trade, tradeOf(111, "USDTTRY", "40", "100", "4000", true))
          operations(result) must_== List(
            (
              "trade/USDTTRY/111",
              "sell",
              Some("TRY"),
              BigDecimal(-4000),
              Some(BigDecimal("0.025")),
              BigDecimal(100),
              "USDT",
              "Buy"
            )
          )
        }

        "selling the base buys the quote coin with cash" >> {
          val result =
            translate(BinanceSource.Trade, tradeOf(112, "USDTTRY", "40", "100", "4000", false))
          operations(result) must_== List(
            (
              "trade/USDTTRY/112",
              "buy",
              Some("TRY"),
              BigDecimal(4000),
              Some(BigDecimal("0.025")),
              BigDecimal(-100),
              "USDT",
              "Sell"
            )
          )
        }

        "a commission in the cash asset is a fee naming the coin" >> {
          val result = translate(
            BinanceSource.Trade,
            tradeOf(111, "USDTTRY", "40", "100", "4000", true, "0.1", "USDT")
          )
          operations(result)
            .find(_._2 == "fee")
            .map(fee => (fee._3, fee._6, fee._7)) must beSome(
            (Some("TRY"), BigDecimal("-0.1"), "USDT")
          )
        }
      }

      "a trade that cannot be translated" >> {
        "is refused when its pair is not known" >> {
          val unknown = tradeOf(113, "DOGEUSDT", "1", "1", "1", true)
          BinanceTranslation.translate(
            record(accountId, BinanceSource.Trade, unknown),
            context,
            PriceBook()
          ) must beLeft[BinanceRecordError](
            BinanceRecordError.UnknownPair(BinanceRecordId("DOGEUSDT/113"), symbol("DOGEUSDT"))
          )
        }

        "is refused when it lacks a quantity, naming the record and the field" >> {
          val without = obj(
            """{"symbol":"BTCUSDT","id":114,"price":"1","quoteQty":"1","isBuyer":true,"time":1773146096789}"""
          )
          BinanceTranslation.translate(
            record(accountId, BinanceSource.Trade, without),
            context,
            PriceBook()
          ) must beLeft[BinanceRecordError](
            BinanceRecordError
              .MissingField(BinanceSource.Trade, Some(BinanceRecordId("BTCUSDT/114")), "qty")
          )
        }

        "is refused when a number is not one" >> {
          val garbled = tradeOf(115, "BTCUSDT", "1", "lots", "1", true)
          BinanceTranslation.translate(
            record(accountId, BinanceSource.Trade, garbled),
            context,
            PriceBook()
          ) must beLeft[BinanceRecordError](
            BinanceRecordError.InvalidField(
              BinanceSource.Trade,
              Some(BinanceRecordId("BTCUSDT/115")),
              "qty",
              "lots"
            )
          )
        }
      }

      "a deposit" >> {
        "of a coin is units coming in with no cash, in the first price quote" >> {
          val result =
            translate(BinanceSource.Deposit, deposit("9876", "BTC", "0.5", "2026-03-10T08:00:00Z"))
          operations(result) must_== List(
            (
              "deposit/9876",
              "security_in",
              Some("BTC"),
              BigDecimal("0.5"),
              None,
              BigDecimal(0),
              "USDC",
              "Deposit"
            )
          )
        }

        "of a cash asset is cash coming in" >> {
          val result =
            translate(BinanceSource.Deposit, deposit("9877", "USDT", "250", "2026-03-10T08:00:00Z"))
          operations(result) must_== List(
            (
              "deposit/9877",
              "deposit",
              None,
              BigDecimal(0),
              None,
              BigDecimal(250),
              "USDT",
              "Deposit"
            )
          )
        }

        "is executed once credited, canceled once rejected, and pending until then" >> {
          val byStatus = List(
            1 -> OperationState.Executed,
            6 -> OperationState.Executed,
            2 -> OperationState.Canceled,
            7 -> OperationState.Canceled,
            0 -> OperationState.Pending,
            8 -> OperationState.Pending
          )
          Fragments.foreach(byStatus) { case (status, state) =>
            s"status $status is $state" >> {
              states(
                translate(
                  BinanceSource.Deposit,
                  deposit("9876", "BTC", "0.5", "2026-03-10T08:00:00Z", status)
                )
              ) must_== Set(state)
            }
          }
        }
      }

      "a withdrawal" >> {
        val applied = "2026-03-10T12:34:56Z"

        "of a coin is units leaving, and its network fee is a sale of the coin at its close and a fee naming no coin" >> {
          val result = translate(
            BinanceSource.Withdrawal,
            withdrawal("5544", "BTC", "0.5", "0.0005", applied),
            book("BTCUSDC" -> "60000")
          )
          operations(result) must_== List(
            (
              "withdrawal/5544",
              "security_out",
              Some("BTC"),
              BigDecimal("-0.5"),
              None,
              BigDecimal(0),
              "USDC",
              "Withdrawal"
            ),
            (
              "withdrawal/5544/fee-sale",
              "sell",
              Some("BTC"),
              BigDecimal("-0.0005"),
              Some(BigDecimal(60000)),
              BigDecimal(30),
              "USDC",
              "Network fee"
            ),
            (
              "withdrawal/5544/fee",
              "fee",
              None,
              BigDecimal(0),
              None,
              BigDecimal(-30),
              "USDC",
              "Network fee"
            )
          )
        }

        "of a coin whose network fee no bar values loses the fee units at their cost, and is noted" >> {
          val result = translate(
            BinanceSource.Withdrawal,
            withdrawal("5544", "BTC", "0.5", "0.0005", applied),
            absent("BTCUSDC", "BTCUSDT")
          )
          operations(result).drop(1) must_== List(
            (
              "withdrawal/5544/fee",
              "security_out",
              Some("BTC"),
              BigDecimal("-0.0005"),
              None,
              BigDecimal(0),
              "USDC",
              "Network fee"
            )
          )
          result.notes.map(_.reason) must_== List(
            BinanceNote.Reason.NetworkFeeUnvalued(asset("BTC"), BigDecimal("0.0005"))
          )
        }

        "of a cash asset is cash leaving with a fee for the network" >> {
          val result =
            translate(BinanceSource.Withdrawal, withdrawal("5545", "USDT", "100", "1", applied))
          operations(result) must_== List(
            (
              "withdrawal/5545",
              "withdrawal",
              None,
              BigDecimal(0),
              None,
              BigDecimal(-100),
              "USDT",
              "Withdrawal"
            ),
            (
              "withdrawal/5545/fee",
              "fee",
              None,
              BigDecimal(0),
              None,
              BigDecimal(-1),
              "USDT",
              "Network fee"
            )
          )
        }

        "without a fee has no fee operation" >> {
          operations(
            translate(BinanceSource.Withdrawal, withdrawal("5546", "USDT", "100", "0", applied))
          ).map(_._1) must_== List("withdrawal/5546")
        }

        "is dated by the time it was applied for" >> {
          translate(
            BinanceSource.Withdrawal,
            withdrawal("5544", "USDT", "5", "0", applied)
          ).operations
            .map(_.occurredAt) must_== List(at("2026-03-10T12:34:56Z"))
        }

        "is executed once complete, canceled once cancelled, rejected or failed, and pending until then" >> {
          val byStatus = List(
            6 -> OperationState.Executed,
            1 -> OperationState.Canceled,
            3 -> OperationState.Canceled,
            5 -> OperationState.Canceled,
            0 -> OperationState.Pending,
            2 -> OperationState.Pending,
            4 -> OperationState.Pending
          )
          Fragments.foreach(byStatus) { case (status, state) =>
            s"status $status is $state" >> {
              states(
                translate(
                  BinanceSource.Withdrawal,
                  withdrawal("5547", "USDT", "5", "0", applied, status)
                )
              ) must_== Set(state)
            }
          }
        }
      }

      "a fiat order" >> {
        val created = "2026-03-10T09:00:00Z"

        "a deposit is the money indicated paid in, with its fee" >> {
          val result =
            translate(BinanceSource.FiatDeposit, fiatOrder("O1", "EUR", "100", "99", "1", created))
          operations(result) must_== List(
            (
              "fiat_deposit/O1",
              "deposit",
              None,
              BigDecimal(0),
              None,
              BigDecimal(100),
              "EUR",
              "Fiat deposit"
            ),
            (
              "fiat_deposit/O1/fee",
              "fee",
              None,
              BigDecimal(0),
              None,
              BigDecimal(-1),
              "EUR",
              "Fiat fee"
            )
          )
        }

        "a withdrawal is the amount paid out, with its fee" >> {
          val result = translate(
            BinanceSource.FiatWithdrawal,
            fiatOrder("O2", "EUR", "50.5", "50", "0.5", created)
          )
          operations(result) must_== List(
            (
              "fiat_withdrawal/O2",
              "withdrawal",
              None,
              BigDecimal(0),
              None,
              BigDecimal(-50),
              "EUR",
              "Fiat withdrawal"
            ),
            (
              "fiat_withdrawal/O2/fee",
              "fee",
              None,
              BigDecimal(0),
              None,
              BigDecimal("-0.5"),
              "EUR",
              "Fiat fee"
            )
          )
        }

        "is executed when successful or finished, canceled when failed, refunded or expired, and pending otherwise" >> {
          val byStatus = List(
            "Successful" -> OperationState.Executed,
            "Finished" -> OperationState.Executed,
            "Failed" -> OperationState.Canceled,
            "Refunded" -> OperationState.Canceled,
            "Expired" -> OperationState.Canceled,
            "Processing" -> OperationState.Pending,
            "Refunding" -> OperationState.Pending,
            "Order Partial Credit Stopped" -> OperationState.Pending
          )
          Fragments.foreach(byStatus) { case (status, state) =>
            s"$status is $state" >> {
              states(
                translate(
                  BinanceSource.FiatDeposit,
                  fiatOrder("O1", "EUR", "100", "99", "1", created, status)
                )
              ) must_== Set(state)
            }
          }
        }
      }

      "a purchase with fiat money" >> {
        val created = "2026-03-10T10:00:00Z"

        "pays the fiat in, buys the coin with it less the fee, and charges the fee to the coin" >> {
          val result = translate(
            BinanceSource.FiatBuy,
            fiatPayment("P1", "EUR", "BTC", "100", "0.001", "2", "98000", created)
          )
          operations(result) must_== List(
            (
              "fiat_buy/P1/deposit",
              "deposit",
              None,
              BigDecimal(0),
              None,
              BigDecimal(100),
              "EUR",
              "Buy crypto"
            ),
            (
              "fiat_buy/P1",
              "buy",
              Some("BTC"),
              BigDecimal("0.001"),
              Some(BigDecimal(98000)),
              BigDecimal(-98),
              "EUR",
              "Buy crypto"
            ),
            (
              "fiat_buy/P1/fee",
              "fee",
              Some("BTC"),
              BigDecimal(0),
              None,
              BigDecimal(-2),
              "EUR",
              "Fiat fee"
            )
          )
        }

        "paid from the account's own fiat balance has no deposit" >> {
          val result = translate(
            BinanceSource.FiatBuy,
            fiatPayment("P1", "EUR", "BTC", "100", "0.001", "2", "98000", created, "Cash Balance")
          )
          operations(result).map(_._1) must_== List("fiat_buy/P1", "fiat_buy/P1/fee")
        }

        "of a stablecoin is an exchange of fiat for it, and its fee names no coin" >> {
          val result = translate(
            BinanceSource.FiatBuy,
            fiatPayment("P2", "EUR", "USDT", "100", "97.9", "2", "1.001", created)
          )
          operations(result).drop(1) must_== List(
            (
              "fiat_buy/P2",
              "exchange",
              None,
              BigDecimal(0),
              None,
              BigDecimal(-98),
              "EUR",
              "Buy crypto"
            ),
            (
              "fiat_buy/P2/received",
              "exchange",
              None,
              BigDecimal(0),
              None,
              BigDecimal("97.9"),
              "USDT",
              "Buy crypto"
            ),
            ("fiat_buy/P2/fee", "fee", None, BigDecimal(0), None, BigDecimal(-2), "EUR", "Fiat fee")
          )
        }

        "is executed when completed, canceled when failed or refunded, and pending otherwise" >> {
          val byStatus = List(
            "Completed" -> OperationState.Executed,
            "Failed" -> OperationState.Canceled,
            "Refunded" -> OperationState.Canceled,
            "Processing" -> OperationState.Pending
          )
          Fragments.foreach(byStatus) { case (status, state) =>
            s"$status is $state" >> {
              states(
                translate(
                  BinanceSource.FiatBuy,
                  fiatPayment(
                    "P1",
                    "EUR",
                    "BTC",
                    "100",
                    "0.001",
                    "2",
                    "98000",
                    created,
                    status = status
                  )
                )
              ) must_== Set(state)
            }
          }
        }
      }

      "a sale for fiat money" >> {
        "sells the coin for the fiat obtained and the fee, which it charges to the coin, and the fiat stays" >> {
          val result = translate(
            BinanceSource.FiatSell,
            fiatPayment("S1", "EUR", "BTC", "0.001", "95", "1", "96000", "2026-03-10T10:00:00Z")
          )
          operations(result) must_== List(
            (
              "fiat_sell/S1",
              "sell",
              Some("BTC"),
              BigDecimal("-0.001"),
              Some(BigDecimal(96000)),
              BigDecimal(96),
              "EUR",
              "Sell crypto"
            ),
            (
              "fiat_sell/S1/fee",
              "fee",
              Some("BTC"),
              BigDecimal(0),
              None,
              BigDecimal(-1),
              "EUR",
              "Fiat fee"
            )
          )
        }
      }

      "a Convert order" >> {
        val created = "2026-03-10T11:00:00Z"
        val orderId = "940708407462087201"

        "of cash for a coin is a buy at the price the amounts give" >> {
          val result =
            translate(BinanceSource.Convert, convert(orderId, "USDT", "30", "BNB", "0.05", created))
          operations(result) must_== List(
            (
              s"convert/$orderId",
              "buy",
              Some("BNB"),
              BigDecimal("0.05"),
              Some(BigDecimal(600)),
              BigDecimal(-30),
              "USDT",
              "Convert"
            )
          )
        }

        "of a coin for cash is a sale at the price the amounts give" >> {
          val result =
            translate(BinanceSource.Convert, convert(orderId, "BNB", "0.05", "USDT", "30", created))
          operations(result) must_== List(
            (
              s"convert/$orderId",
              "sell",
              Some("BNB"),
              BigDecimal("-0.05"),
              Some(BigDecimal(600)),
              BigDecimal(30),
              "USDT",
              "Convert"
            )
          )
        }

        "of one cash asset for another is two exchange operations" >> {
          val result = translate(
            BinanceSource.Convert,
            convert(orderId, "USDC", "100", "USDT", "99.9", created)
          )
          operations(result).map(op => (op._1, op._2, op._6, op._7)) must_== List(
            (s"convert/$orderId", "exchange", BigDecimal(-100), "USDC"),
            (s"convert/$orderId/received", "exchange", BigDecimal("99.9"), "USDT")
          )
        }

        "of a coin for a coin is valued like a trade between coins, with no commission" >> {
          val bar = "2026-03-10T11:00:00Z"
          val prices =
            PriceBook(found = Map(BinanceMinute(symbol("BTCUSDC"), at(bar)) -> BigDecimal(60000)))
          val result = translate(
            BinanceSource.Convert,
            convert(orderId, "BTC", "0.05", "ETH", "1", created),
            prices
          )
          operations(result).map(op => (op._1, op._2, op._6, op._7)) must_== List(
            (s"convert/$orderId", "sell", BigDecimal(3000), "USDC"),
            (s"convert/$orderId/received", "buy", BigDecimal(-3000), "USDC")
          )
        }

        "is executed when it succeeded, canceled when it failed, and pending otherwise" >> {
          val byStatus = List(
            "SUCCESS" -> OperationState.Executed,
            "FAIL" -> OperationState.Canceled,
            "PROCESS" -> OperationState.Pending,
            "ACCEPT_SUCCESS" -> OperationState.Pending
          )
          Fragments.foreach(byStatus) { case (status, state) =>
            s"$status is $state" >> {
              states(
                translate(
                  BinanceSource.Convert,
                  convert(orderId, "USDT", "30", "BNB", "0.05", created, status)
                )
              ) must_== Set(state)
            }
          }
        }
      }

      "every operation of a record carries the record's time and account" >> {
        val result = translate(
          BinanceSource.Trade,
          tradeOf(101, "BTCUSDT", "50000", "0.2", "10000", true, "0.01", "BNB"),
          book("BNBUSDT" -> "600")
        )
        result.operations.map(op => (op.occurredAt, op.accountId)).distinct must_==
          List((Instant.parse(tradeTime), accountId))
      }
    }
  }
}
