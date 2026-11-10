package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.testkit.IbkrFakes
import st.btu.trading.pillar.testkit.IbkrFakes.instrumentKey

import java.time.{Instant, LocalDate, ZoneId}

class IbkrRowTranslationSpec extends Specification {

  private val accountId = AccountId(7)
  private val newYork = ZoneId.of("America/New_York")
  private val reportDate = LocalDate.of(2026, 1, 16)

  // 09:30 on 15 January 2026 in New York, where it is winter time, five hours behind UTC.
  private val dateTime = "20260115;093000"
  private val occurredAt = Instant.parse("2026-01-15T14:30:00Z")

  private val usd = Currency("USD")
  private val eur = Currency("EUR")
  private val apple = instrumentKey("265598")

  private def row(
    section: IbkrSection,
    id: String,
    defaults: Map[String, String],
    overrides: Seq[(String, String)]
  ): IbkrStatementRow =
    IbkrFakes.row(accountId, section, id, reportDate, (defaults ++ overrides).toSeq*)

  private def translate(
    row: IbkrStatementRow,
    canceled: Set[IbkrTransactionId] = Set.empty
  ): Either[IbkrStatementError, List[AccountOperation]] =
    IbkrRowTranslation.translate(row, newYork, canceled)

  private val tradeDefaults = Map(
    "assetCategory" -> "STK",
    "symbol" -> "AAPL",
    "conid" -> "265598",
    "currency" -> "USD",
    "buySell" -> "BUY",
    "quantity" -> "10",
    "tradePrice" -> "150.25",
    "proceeds" -> "-1502.50",
    "dateTime" -> dateTime
  )

  // A purchase of 10 shares of AAPL for 1502.50 dollars, unless the overrides say otherwise.
  private def trade(id: String = "1001", overrides: (String, String)*): IbkrStatementRow =
    row(IbkrSection.Trade, id, tradeDefaults, overrides)

  private val conversionDefaults = Map(
    "assetCategory" -> "CASH",
    "symbol" -> "EUR.USD",
    "conid" -> "12087792",
    "currency" -> "USD",
    "buySell" -> "BUY",
    "quantity" -> "1000",
    "tradePrice" -> "1.1",
    "proceeds" -> "-1100",
    "dateTime" -> dateTime
  )

  // A purchase of 1000 euros for 1100 dollars, unless the overrides say otherwise.
  private def conversion(id: String = "2001", overrides: (String, String)*): IbkrStatementRow =
    row(IbkrSection.Trade, id, conversionDefaults, overrides)

  private val cancelRow = trade(
    "1002",
    "transactionType" -> "TradeCancel",
    "notes" -> "Ca",
    "origTransactionID" -> "1001",
    "buySell" -> "SELL (Ca.)",
    "quantity" -> "-10",
    "proceeds" -> "1502.50"
  )

  private def cash(
    id: String,
    cashType: String,
    amount: String,
    overrides: (String, String)*
  ): IbkrStatementRow =
    row(
      IbkrSection.CashTransaction,
      id,
      Map("type" -> cashType, "amount" -> amount, "currency" -> "USD", "dateTime" -> dateTime),
      overrides
    )

  private val transferDefaults = Map(
    "type" -> "ACATS",
    "direction" -> "IN",
    "assetCategory" -> "STK",
    "symbol" -> "MSFT",
    "conid" -> "272093",
    "currency" -> "USD",
    "quantity" -> "5",
    "transferPrice" -> "400",
    "dateTime" -> dateTime
  )

  private def transfer(id: String = "3001", overrides: (String, String)*): IbkrStatementRow =
    row(IbkrSection.Transfer, id, transferDefaults, overrides)

  private def corporateAction(
    id: String,
    quantity: String,
    overrides: (String, String)*
  ): IbkrStatementRow =
    row(
      IbkrSection.CorporateAction,
      id,
      Map(
        "type" -> "RS",
        "conid" -> "265598",
        "currency" -> "USD",
        "quantity" -> quantity,
        "dateTime" -> dateTime
      ),
      overrides
    )

  // The operations that translate to, as (ref, kind, quantity, amount, currency).
  private def shape(
    translation: Either[IbkrStatementError, List[AccountOperation]]
  ): Either[IbkrStatementError, List[
    (OperationRef, OperationKind, BigDecimal, BigDecimal, Currency)
  ]] =
    translation.map(_.map(op => (op.ref, op.kind, op.quantity, op.amount, op.currency)))

  private def states(
    translation: Either[IbkrStatementError, List[AccountOperation]]
  ): Either[IbkrStatementError, List[OperationState]] =
    translation.map(_.map(_.state))

  "IbkrRowTranslation" >> {
    "translate" >> {

      "stock trades" >> {
        "a positive quantity is a buy at the trade price, paid for with the proceeds" >> {
          translate(trade()) must_== Right(
            List(
              AccountOperation(
                accountId,
                OperationRef("trade/1001"),
                OperationKind.Buy,
                OperationState.Executed,
                occurredAt,
                Some(apple),
                BigDecimal(10),
                Some(BigDecimal("150.25")),
                BigDecimal("-1502.50"),
                usd,
                BrokerOperationType("BUY")
              )
            )
          )
        }

        "a negative quantity is a sell, with the proceeds received" >> {
          val sale = trade("1003", "buySell" -> "SELL", "quantity" -> "-4", "proceeds" -> "620")

          shape(translate(sale)) must_== Right(
            List(
              (OperationRef("trade/1003"), OperationKind.Sell, BigDecimal(-4), BigDecimal(620), usd)
            )
          )
        }

        "no units is other, and moves no position" >> {
          shape(translate(trade("1003", "quantity" -> "0", "proceeds" -> "0"))) must_== Right(
            List(
              (OperationRef("trade/1003"), OperationKind.Other, BigDecimal(0), BigDecimal(0), usd)
            )
          )
        }

        "numbers may carry thousands separators" >> {
          val large = trade("1003", "quantity" -> "1,000", "proceeds" -> "-150,250.50")

          shape(translate(large)) must_== Right(
            List(
              (
                OperationRef("trade/1003"),
                OperationKind.Buy,
                BigDecimal(1000),
                BigDecimal("-150250.50"),
                usd
              )
            )
          )
        }

        "a commission is a fee of its own naming the instrument, in the currency it was charged in" >> {
          val charged = trade("1001", "ibCommission" -> "-1.5", "ibCommissionCurrency" -> "EUR")

          translate(charged).map(_.drop(1)) must_== Right(
            List(
              AccountOperation(
                accountId,
                OperationRef("trade/1001/commission"),
                OperationKind.Fee,
                OperationState.Executed,
                occurredAt,
                Some(apple),
                BigDecimal(0),
                None,
                BigDecimal("-1.5"),
                eur,
                BrokerOperationType("Commission")
              )
            )
          )
        }

        "a commission with no currency of its own is in the currency of the trade" >> {
          val charged = trade("1001", "ibCommission" -> "-1.5")

          shape(translate(charged)).map(_.map(_._5)) must_== Right(List(usd, usd))
        }

        "a transaction tax is a fee in the currency of the trade" >> {
          val taxed = trade("1001", "taxes" -> "-3.2", "ibCommissionCurrency" -> "EUR")

          translate(taxed).map(_.drop(1)) must beRight[List[AccountOperation]].like {
            case List(tax) =>
              tax.ref must_== OperationRef("trade/1001/taxes")
              tax.kind must_== OperationKind.Fee
              tax.instrument must beSome(apple)
              tax.amount must_== BigDecimal("-3.2")
              tax.currency must_== usd
              tax.brokerType must_== BrokerOperationType("Transaction tax")
          }
        }

        "a commission and a tax of zero are no fees" >> {
          val free = trade("1001", "ibCommission" -> "0", "taxes" -> "0.00")

          translate(free).map(_.size) must_== Right(1)
        }

        "the commission and the tax come after the trade, each as its own operation" >> {
          val charged = trade("1001", "ibCommission" -> "-1", "taxes" -> "-2")

          shape(translate(charged)).map(_.map(_._1)) must_== Right(
            List(
              OperationRef("trade/1001"),
              OperationRef("trade/1001/commission"),
              OperationRef("trade/1001/taxes")
            )
          )
        }
      }

      "currency conversions" >> {
        "buying the first currency is an exchange in each, the first by quantity and the second by proceeds" >> {
          translate(conversion()) must_== Right(
            List(
              AccountOperation(
                accountId,
                OperationRef("trade/2001/EUR"),
                OperationKind.Exchange,
                OperationState.Executed,
                occurredAt,
                None,
                BigDecimal(0),
                None,
                BigDecimal(1000),
                eur,
                BrokerOperationType("Currency exchange")
              ),
              AccountOperation(
                accountId,
                OperationRef("trade/2001/USD"),
                OperationKind.Exchange,
                OperationState.Executed,
                occurredAt,
                None,
                BigDecimal(0),
                None,
                BigDecimal(-1100),
                usd,
                BrokerOperationType("Currency exchange")
              )
            )
          )
        }

        "selling the first currency gives the opposite signs" >> {
          val sale = conversion("2002", "quantity" -> "-1000", "proceeds" -> "1100")

          shape(translate(sale)) must_== Right(
            List(
              (
                OperationRef("trade/2002/EUR"),
                OperationKind.Exchange,
                BigDecimal(0),
                BigDecimal(-1000),
                eur
              ),
              (
                OperationRef("trade/2002/USD"),
                OperationKind.Exchange,
                BigDecimal(0),
                BigDecimal(1100),
                usd
              )
            )
          )
        }

        "the commission is a fee naming no instrument" >> {
          val charged = conversion("2001", "ibCommission" -> "-2", "ibCommissionCurrency" -> "USD")

          translate(charged).map(_.drop(2)) must beRight[List[AccountOperation]].like {
            case List(fee) =>
              fee.ref must_== OperationRef("trade/2001/commission")
              fee.kind must_== OperationKind.Fee
              fee.instrument must beNone
              fee.amount must_== BigDecimal(-2)
              fee.currency must_== usd
          }
        }

        "a pair that does not end in the currency of the trade is other, carrying the proceeds" >> {
          val odd = conversion("2003", "symbol" -> "EUR.GBP")

          shape(translate(odd)) must_== Right(
            List(
              (
                OperationRef("trade/2003"),
                OperationKind.Other,
                BigDecimal(0),
                BigDecimal(-1100),
                usd
              )
            )
          )
        }

        "a symbol that is not a pair is other, carrying the proceeds" >> {
          val odd = conversion("2003", "symbol" -> "EURUSD")

          shape(translate(odd)) must_== Right(
            List(
              (
                OperationRef("trade/2003"),
                OperationKind.Other,
                BigDecimal(0),
                BigDecimal(-1100),
                usd
              )
            )
          )
        }
      }

      "trades of another asset category" >> {
        "are other, carrying their cash and no position" >> {
          val option = trade(
            "5001",
            "assetCategory" -> "OPT",
            "symbol" -> "AAPL  260116C00200000",
            "conid" -> "700001",
            "quantity" -> "1",
            "proceeds" -> "-250",
            "ibCommission" -> "-0.65"
          )

          translate(option) must beRight[List[AccountOperation]].like { case List(premium, fee) =>
            premium.kind must_== OperationKind.Other
            premium.quantity must_== BigDecimal(0)
            premium.amount must_== BigDecimal(-250)
            premium.price must beNone
            premium.instrument must beSome(instrumentKey("700001"))
            fee.ref must_== OperationRef("trade/5001/commission")
          }
        }
      }

      "cancellations" >> {
        "a row of type TradeCancel is canceled, with every operation it gives" >> {
          val cancelWithFee = trade(
            "1002",
            "transactionType" -> "TradeCancel",
            "origTransactionID" -> "1001",
            "ibCommission" -> "1"
          )

          states(translate(cancelWithFee)) must_==
            Right(List(OperationState.Canceled, OperationState.Canceled))
        }

        "a row noted with the code Ca among others is canceled" >> {
          states(translate(trade("1002", "notes" -> "P;Ca"))) must_==
            Right(List(OperationState.Canceled))
        }

        "a row noted with a code that only starts with Ca is not" >> {
          states(translate(trade("1002", "notes" -> "Cal;P"))) must_==
            Right(List(OperationState.Executed))
        }

        "a trade named by a cancelling row is canceled, with its fees" >> {
          val charged = trade("1001", "ibCommission" -> "-1", "taxes" -> "-2")

          states(translate(charged, canceled = Set(IbkrTransactionId("1001")))) must_==
            Right(List.fill(3)(OperationState.Canceled))
        }

        "a trade the cancelling rows do not name stays executed" >> {
          states(translate(trade("1003"), canceled = Set(IbkrTransactionId("1001")))) must_==
            Right(List(OperationState.Executed))
        }

        "a correction counts: the original and the cancel row are canceled and the new trade is not" >> {
          val canceled = IbkrRowTranslation.canceledTransactions(List(trade("1001"), cancelRow))
          val correction = trade("1003", "quantity" -> "9", "proceeds" -> "-1352.25")

          List(trade("1001"), cancelRow, correction)
            .map(r => states(translate(r, canceled)).map(_.distinct)) must_== List(
            Right(List(OperationState.Canceled)),
            Right(List(OperationState.Canceled)),
            Right(List(OperationState.Executed))
          )
        }

        "the trades that cancelling rows name are found among the rows" >> {
          IbkrRowTranslation.canceledTransactions(
            List(trade("1001"), cancelRow, trade("1003"))
          ) must_== Set(IbkrTransactionId("1001"))
        }

        "a cancelling row that names no trade cancels none" >> {
          val unnamed =
            trade("1002", "transactionType" -> "TradeCancel", "origTransactionID" -> "-")

          IbkrRowTranslation.canceledTransactions(List(trade("1001"), unnamed)) must beEmpty
        }

        "a row of another section is never a cancelling row" >> {
          val note = cash("2001", "Dividends", "5", "notes" -> "Ca", "origTransactionID" -> "1001")

          IbkrRowTranslation.canceledTransactions(List(note)) must beEmpty
        }
      }

      "cash transactions" >> {
        val typesAndKinds: List[(String, String, OperationKind)] = List(
          ("Deposits/Withdrawals", "1000", OperationKind.Deposit),
          ("Deposits & Withdrawals", "1000", OperationKind.Deposit),
          ("Deposits/Withdrawals", "-250", OperationKind.Withdrawal),
          ("Deposits & Withdrawals", "-250", OperationKind.Withdrawal),
          ("Dividends", "12.34", OperationKind.Dividend),
          ("Payment In Lieu Of Dividends", "5", OperationKind.Dividend),
          ("Withholding Tax", "-1.85", OperationKind.Tax),
          ("Broker Interest Received", "0.5", OperationKind.Interest),
          ("Broker Interest Paid", "-0.25", OperationKind.Interest),
          ("Bond Interest Received", "10", OperationKind.Coupon),
          ("Bond Interest Paid", "-2", OperationKind.Coupon),
          ("Other Fees", "-3", OperationKind.Fee),
          ("Advisor Fees", "-4", OperationKind.Fee),
          ("Commission Adjustments", "0.7", OperationKind.Fee),
          ("Price Adjustments", "1", OperationKind.Other)
        )

        Fragments.foreach(typesAndKinds) { case (cashType, amount, kind) =>
          s"$cashType of $amount is ${kind.value}" >> {
            translate(cash("2001", cashType, amount)).map(_.map(_.kind)) must_== Right(List(kind))
          }
        }

        "keeps the amount and its sign as IB gives it, a refund of tax withheld included" >> {
          shape(translate(cash("2002", "Withholding Tax", "0.93"))) must_== Right(
            List(
              (
                OperationRef("cash_transaction/2002"),
                OperationKind.Tax,
                BigDecimal(0),
                BigDecimal("0.93"),
                usd
              )
            )
          )
        }

        "a dividend reversed is a dividend of a negative amount" >> {
          shape(translate(cash("2003", "Dividends", "-12.34"))) must_== Right(
            List(
              (
                OperationRef("cash_transaction/2003"),
                OperationKind.Dividend,
                BigDecimal(0),
                BigDecimal("-12.34"),
                usd
              )
            )
          )
        }

        "an amount of zero is no deposit or withdrawal" >> {
          translate(cash("2001", "Deposits/Withdrawals", "0")).map(_.map(_.kind)) must_==
            Right(List(OperationKind.Other))
        }

        "names the instrument when the row has a contract id, and none otherwise" >> {
          val dividend = cash("2001", "Dividends", "12.34", "conid" -> "265598")
          val deposit = cash("2002", "Deposits/Withdrawals", "1000", "conid" -> "-")

          translate(dividend).map(_.map(_.instrument)) must_== Right(List(Some(apple)))
          translate(deposit).map(_.map(_.instrument)) must_== Right(List(None))
        }

        "shows IB's type of the row as the broker's name for the operation" >> {
          translate(cash("2001", "Other Fees", "-3")).map(_.map(_.brokerType)) must_==
            Right(List(BrokerOperationType("Other Fees")))
        }

        "is dated by the time of the row in the statement zone" >> {
          translate(cash("2001", "Dividends", "5", "dateTime" -> "20260115;202000"))
            .map(_.map(_.occurredAt)) must_== Right(List(Instant.parse("2026-01-16T01:20:00Z")))
        }
      }

      "transfers" >> {
        "stock transferred in is security_in with its quantity, at the transfer price" >> {
          translate(transfer()) must_== Right(
            List(
              AccountOperation(
                accountId,
                OperationRef("transfer/3001"),
                OperationKind.SecurityIn,
                OperationState.Executed,
                occurredAt,
                Some(instrumentKey("272093")),
                BigDecimal(5),
                Some(BigDecimal(400)),
                BigDecimal(0),
                usd,
                BrokerOperationType("ACATS IN")
              )
            )
          )
        }

        "stock transferred out is security_out with a negative quantity" >> {
          val out = transfer("3002", "direction" -> "OUT", "quantity" -> "-4")

          translate(out).map(_.map(op => (op.kind, op.quantity, op.brokerType))) must_== Right(
            List((OperationKind.SecurityOut, BigDecimal(-4), BrokerOperationType("ACATS OUT")))
          )
        }

        "the direction decides when the quantity is given without a sign" >> {
          val out = transfer("3002", "direction" -> "OUT", "quantity" -> "4")

          translate(out).map(_.map(op => (op.kind, op.quantity))) must_== Right(
            List((OperationKind.SecurityOut, BigDecimal(-4)))
          )
        }

        "the sign of the quantity decides when IB names no direction" >> {
          val out = transfer("3002", "direction" -> "-", "quantity" -> "-4")

          translate(out).map(_.map(op => (op.kind, op.quantity))) must_== Right(
            List((OperationKind.SecurityOut, BigDecimal(-4)))
          )
        }

        "no price is given where IB gives none" >> {
          translate(transfer("3002", "transferPrice" -> "-")).map(_.map(_.price)) must_==
            Right(List(None))
        }

        "cash that comes with stock is an other operation of its own" >> {
          val withCash = transfer("3002", "cashTransfer" -> "250")

          shape(translate(withCash)) must_== Right(
            List(
              (
                OperationRef("transfer/3002"),
                OperationKind.SecurityIn,
                BigDecimal(5),
                BigDecimal(0),
                usd
              ),
              (
                OperationRef("transfer/3002/cash"),
                OperationKind.Other,
                BigDecimal(0),
                BigDecimal(250),
                usd
              )
            )
          )
        }

        "a transfer of cash alone is other, carrying the cash amount" >> {
          val cashOnly = transfer(
            "3003",
            "assetCategory" -> "CASH",
            "symbol" -> "USD",
            "conid" -> "-",
            "quantity" -> "-",
            "cashTransfer" -> "1000"
          )

          shape(translate(cashOnly)) must_== Right(
            List(
              (
                OperationRef("transfer/3003"),
                OperationKind.Other,
                BigDecimal(0),
                BigDecimal(1000),
                usd
              )
            )
          )
        }
      }

      "corporate actions" >> {
        "a leg that grows a position is security_in with its quantity and its cash" >> {
          val received = corporateAction("4001", "480", "proceeds" -> "12.5")

          translate(received) must_== Right(
            List(
              AccountOperation(
                accountId,
                OperationRef("corporate_action/4001"),
                OperationKind.SecurityIn,
                OperationState.Executed,
                occurredAt,
                Some(apple),
                BigDecimal(480),
                None,
                BigDecimal("12.5"),
                usd,
                BrokerOperationType("RS")
              )
            )
          )
        }

        "a leg that shrinks a position is security_out with a negative quantity" >> {
          val removed = corporateAction("4002", "-12000")

          translate(removed).map(_.map(op => (op.kind, op.quantity))) must_== Right(
            List((OperationKind.SecurityOut, BigDecimal(-12000)))
          )
        }

        "a leg with no quantity is other, carrying the cash" >> {
          val cashOnly = corporateAction("4003", "0", "proceeds" -> "30", "conid" -> "-")

          shape(translate(cashOnly)) must_== Right(
            List(
              (
                OperationRef("corporate_action/4003"),
                OperationKind.Other,
                BigDecimal(0),
                BigDecimal(30),
                usd
              )
            )
          )
        }
      }

      "occurredAt" >> {
        "follows the daylight saving time of the statement zone" >> {
          val summer = trade("1001", "dateTime" -> "20260715;093000")

          translate(summer).map(_.map(_.occurredAt)) must_== Right(
            List(Instant.parse("2026-07-15T13:30:00Z"))
          )
        }

        "is the start of the day when the row carries a date alone" >> {
          val dated = trade("1001", "dateTime" -> "20260115")

          translate(dated).map(_.map(_.occurredAt)) must_== Right(
            List(Instant.parse("2026-01-15T05:00:00Z"))
          )
        }

        "comes from the date of a row that has no time" >> {
          val dated = transfer("3001", "dateTime" -> "-", "date" -> "20260114")

          translate(dated).map(_.map(_.occurredAt)) must_== Right(
            List(Instant.parse("2026-01-14T05:00:00Z"))
          )
        }

        "comes from the report date of a row that has neither" >> {
          val undated = trade("1001", "dateTime" -> "")

          translate(undated).map(_.map(_.occurredAt)) must_== Right(
            List(Instant.parse("2026-01-16T05:00:00Z"))
          )
        }
      }

      "empty values" >> {
        "a placeholder is no price, no commission and no tax" >> {
          val bare = trade(
            "1001",
            "tradePrice" -> "--",
            "ibCommission" -> "-",
            "ibCommissionCurrency" -> "N/A",
            "taxes" -> "",
            "origTransactionID" -> "-"
          )

          translate(bare).map(_.map(op => (op.ref, op.price))) must_==
            Right(List((OperationRef("trade/1001"), None)))
        }

        "a price of zero is no price" >> {
          translate(trade("1001", "tradePrice" -> "0")).map(_.map(_.price)) must_== Right(
            List(None)
          )
        }
      }

      "refs" >> {
        val rows = List(
          "a stock trade with its fees" -> trade("1001", "ibCommission" -> "-1", "taxes" -> "-2"),
          "a currency conversion with its commission" ->
            conversion("2001", "ibCommission" -> "-2"),
          "a trade of another asset category with its commission" ->
            trade("5001", "assetCategory" -> "OPT", "ibCommission" -> "-0.65"),
          "a cancelling row with its fee" -> cancelRow.copy(attributes =
            cancelRow.attributes + ("ibCommission" -> "1")
          ),
          "a cash transaction" -> cash("2001", "Dividends", "5"),
          "a transfer with cash" -> transfer("3001", "cashTransfer" -> "250"),
          "a corporate action leg" -> corporateAction("4001", "-12")
        )

        Fragments.foreach(rows) { case (name, row) =>
          s"of $name are the row's own ref and refs under it, each once" >> {
            translate(row).map { operations =>
              val refs = operations.map(_.ref)
              (operations.nonEmpty, refs.forall(row.owns), refs.distinct.size == refs.size)
            } must_== Right((true, true, true))
          }
        }
      }

      "rows that cannot be translated" >> {
        "fail when an attribute the translation needs is missing" >> {
          val noProceeds = trade("1001", "proceeds" -> "-")

          translate(noProceeds) must_== Left(
            IbkrStatementError
              .MissingAttribute(IbkrSection.Trade, Some(IbkrTransactionId("1001")), "proceeds")
          )
        }

        "fail when a stock trade names no instrument" >> {
          translate(trade("1001", "conid" -> "-")) must_== Left(
            IbkrStatementError
              .MissingAttribute(IbkrSection.Trade, Some(IbkrTransactionId("1001")), "conid")
          )
        }

        "fail when a number is not a number" >> {
          translate(trade("1001", "quantity" -> "ten")) must_== Left(
            IbkrStatementError.InvalidAttribute(
              IbkrSection.Trade,
              Some(IbkrTransactionId("1001")),
              "quantity",
              "ten"
            )
          )
        }

        "fail when a time is not in the format of the query" >> {
          translate(trade("1001", "dateTime" -> "15/01/2026 09:30")) must_== Left(
            IbkrStatementError.InvalidAttribute(
              IbkrSection.Trade,
              Some(IbkrTransactionId("1001")),
              "dateTime",
              "15/01/2026 09:30"
            )
          )
        }
      }
    }
  }
}
