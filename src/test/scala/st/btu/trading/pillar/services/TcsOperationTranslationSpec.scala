package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import ru.tinkoff.piapi.contract.v1.operations.OperationType as ContractOperationType
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.AnalyticsFakes

import java.time.{Instant, ZoneOffset, ZonedDateTime}

class TcsOperationTranslationSpec extends Specification {

  private val accountId = AccountId(7)
  private val occurredAt = Instant.parse("2026-03-02T09:30:00Z")
  private val executed = "OPERATION_STATE_EXECUTED"

  // Every operation type of the Tinkoff contract, by the kind it is translated to.
  private val typeNamesByKind: List[(OperationKind, List[String])] = List(
    OperationKind.Buy -> List("BUY", "BUY_CARD", "BUY_MARGIN", "DELIVERY_BUY"),
    OperationKind.Sell ->
      List("SELL", "SELL_CARD", "SELL_MARGIN", "DELIVERY_SELL", "BOND_REPAYMENT_FULL"),
    OperationKind.SecurityIn -> List("INPUT_SECURITIES"),
    OperationKind.SecurityOut -> List("OUTPUT_SECURITIES"),
    OperationKind.Deposit -> List("INPUT", "INPUT_SWIFT", "INPUT_ACQUIRING", "INP_MULTI"),
    OperationKind.Withdrawal -> List("OUTPUT", "OUTPUT_SWIFT", "OUTPUT_ACQUIRING", "OUT_MULTI"),
    OperationKind.Dividend -> List("DIVIDEND", "DIV_EXT"),
    OperationKind.Coupon -> List("COUPON"),
    OperationKind.Interest -> List("OVERNIGHT", "OVER_INCOME"),
    OperationKind.Fee -> List(
      "BROKER_FEE",
      "SERVICE_FEE",
      "MARGIN_FEE",
      "SUCCESS_FEE",
      "TRACK_MFEE",
      "TRACK_PFEE",
      "CASH_FEE",
      "OUT_FEE",
      "ADVICE_FEE",
      "OTHER_FEE",
      "OUT_STAMP_DUTY",
      "OUTPUT_PENALTY",
      "OVER_COM",
      "FUNDING"
    ),
    OperationKind.Tax -> List(
      "TAX",
      "BOND_TAX",
      "DIVIDEND_TAX",
      "BENEFIT_TAX",
      "TAX_PROGRESSIVE",
      "BOND_TAX_PROGRESSIVE",
      "DIVIDEND_TAX_PROGRESSIVE",
      "BENEFIT_TAX_PROGRESSIVE",
      "TAX_CORRECTION",
      "TAX_CORRECTION_PROGRESSIVE",
      "TAX_CORRECTION_COUPON",
      "TAX_REPO",
      "TAX_REPO_PROGRESSIVE",
      "TAX_REPO_HOLD",
      "TAX_REPO_HOLD_PROGRESSIVE",
      "TAX_REPO_REFUND",
      "TAX_REPO_REFUND_PROGRESSIVE"
    ),
    OperationKind.Other -> List(
      "UNSPECIFIED",
      "BOND_REPAYMENT",
      "DIVIDEND_TRANSFER",
      "ACCRUING_VARMARGIN",
      "WRITING_OFF_VARMARGIN",
      "TRANS_IIS_BS",
      "TRANS_BS_BS",
      "OVER_PLACEMENT",
      "OPTION_EXPIRATION",
      "FUTURE_EXPIRATION",
      "OTHER",
      "DFA_REDEMPTION",
      "PRIMARY_ORDER"
    )
  )

  private def operation(
    operationType: String = "OPERATION_TYPE_BUY",
    status: String = executed,
    quantity: Int = 0,
    quantityRest: Int = 0,
    price: BigDecimal = 0,
    amount: BigDecimal = 0,
    figi: Option[String] = Some("BBG004730N88"),
    extId: String = "op-1",
    createdAt: ZonedDateTime = ZonedDateTime.ofInstant(occurredAt, ZoneOffset.UTC)
  ): TcsPortfolioOperation =
    AnalyticsFakes
      .operation(accountId, operationType, amount, createdAt, figi, quantity, "RUB", status, price)
      .copy(extId = PortfolioOperationExtId(extId), quantityRest = quantityRest)

  private def translated(op: TcsPortfolioOperation): AccountOperation =
    TcsOperationTranslation.translate(op)

  "TcsOperationTranslation" >> {
    "translate" >> {
      "kind" >> {
        "of each operation type of the contract" >> {
          Fragments.foreach(typeNamesByKind.flatMap { case (kind, names) =>
            names.map(kind -> _)
          }) { case (kind, name) =>
            s"is ${kind.value} for OPERATION_TYPE_$name" >> {
              translated(operation(s"OPERATION_TYPE_$name")).kind must_== kind
            }
          }
        }

        "is other for a type the library does not name" >> {
          translated(operation("OPERATION_TYPE_SOME_FUTURE_TYPE")).kind must_== OperationKind.Other
        }

        "covers every operation type of the Tinkoff contract" >> {
          typeNamesByKind.flatMap(_._2).map("OPERATION_TYPE_" + _).toSet must_==
            ContractOperationType.values.map(_.name).toSet
        }
      }

      "state" >> {
        "is executed for an operation Tinkoff reports as executed" >> {
          translated(operation(status = executed)).state must_== OperationState.Executed
        }

        "is canceled for an operation Tinkoff reports as canceled" >> {
          translated(operation(status = "OPERATION_STATE_CANCELED")).state must_==
            OperationState.Canceled
        }

        "is pending for an operation still in progress" >> {
          translated(operation(status = "OPERATION_STATE_PROGRESS")).state must_==
            OperationState.Pending
        }

        "is pending for a state the library does not know" >> {
          translated(operation(status = "OPERATION_STATE_SOME_FUTURE_STATE")).state must_==
            OperationState.Pending
        }
      }

      "quantity" >> {
        "is the executed units of a buy, 6 of 10 ordered with 4 unfilled" >> {
          translated(operation(quantity = 10, quantityRest = 4)).quantity must_== BigDecimal(6)
        }

        "is negative for a sell" >> {
          translated(operation("OPERATION_TYPE_SELL", quantity = 10)).quantity must_==
            BigDecimal(-10)
        }

        "is positive for securities transferred in" >> {
          translated(operation("OPERATION_TYPE_INPUT_SECURITIES", quantity = 3)).quantity must_==
            BigDecimal(3)
        }

        "is negative for securities transferred out" >> {
          translated(operation("OPERATION_TYPE_OUTPUT_SECURITIES", quantity = 3)).quantity must_==
            BigDecimal(-3)
        }

        "is zero for a dividend, whatever units Tinkoff reports with it" >> {
          translated(operation("OPERATION_TYPE_DIVIDEND", quantity = 100)).quantity must_==
            BigDecimal(0)
        }

        "is zero for a type the library does not name" >> {
          translated(operation("OPERATION_TYPE_SOME_FUTURE_TYPE", quantity = 5)).quantity must_==
            BigDecimal(0)
        }
      }

      "price" >> {
        "is the operation's price when it is not zero" >> {
          translated(operation(price = BigDecimal("100.15"))).price must beSome(
            BigDecimal("100.15")
          )
        }

        "is absent when the operation's price is zero" >> {
          translated(operation(price = BigDecimal("0.000000000"))).price must beNone
        }
      }

      "ref" >> {
        "joins the operation's id and its type with a slash" >> {
          translated(operation("OPERATION_TYPE_BUY", extId = "op-1")).ref must_==
            OperationRef("op-1/OPERATION_TYPE_BUY")
        }

        "differs between two operations of different types under one id" >> {
          val buy = translated(operation("OPERATION_TYPE_BUY", extId = "op-1"))
          val fee = translated(operation("OPERATION_TYPE_BROKER_FEE", extId = "op-1"))

          buy.ref must_!= fee.ref
        }
      }

      "amount" >> {
        "keeps a tax refund positive" >> {
          val refund = translated(operation("OPERATION_TYPE_TAX_REPO_REFUND", amount = 25))

          refund.kind must_== OperationKind.Tax
          refund.amount must_== BigDecimal(25)
        }
      }

      "instrument" >> {
        "is the operation's FIGI at Tinkoff" >> {
          translated(operation(figi = Some("BBG004730N88"))).instrument must beSome(
            InstrumentKey(Broker.Tinkoff, BrokerInstrumentId("BBG004730N88"))
          )
        }

        "is absent for an operation with no FIGI" >> {
          translated(operation("OPERATION_TYPE_INPUT", figi = None)).instrument must beNone
        }
      }

      "occurredAt" >> {
        "is the instant the operation is dated, in whatever zone Tinkoff gave it" >> {
          val moscow = ZoneOffset.ofHours(3)
          val inMoscow = ZonedDateTime.ofInstant(occurredAt, moscow)

          translated(operation(createdAt = inMoscow)).occurredAt must_== occurredAt
        }
      }

      "brokerType" >> {
        "is the operation type name as received, a name the library does not know included" >> {
          translated(operation("OPERATION_TYPE_SOME_FUTURE_TYPE")).brokerType must_==
            BrokerOperationType("OPERATION_TYPE_SOME_FUTURE_TYPE")
        }
      }
    }
  }
}
