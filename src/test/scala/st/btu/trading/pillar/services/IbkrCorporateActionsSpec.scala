package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.ibkr.IbkrTransactionId.value
import st.btu.trading.pillar.services.IbkrCorporateActions.{ActionSummary, Notice}
import st.btu.trading.pillar.testkit.IbkrFakes
import st.btu.trading.pillar.testkit.IbkrFakes.instrumentKey

import java.time.{Instant, LocalDate, ZoneId}

class IbkrCorporateActionsSpec extends Specification {

  private val accountId = AccountId(7)
  private val newYork = ZoneId.of("America/New_York")
  private val reportDate = LocalDate.of(2026, 1, 16)

  // 09:30 on 15 January 2026 in New York, five hours behind UTC then.
  private val dateTime = "20260115;093000"
  private val occurredAt = Instant.parse("2026-01-15T14:30:00Z")
  private val actionDay = LocalDate.of(2026, 1, 15)

  private val usd = Currency("USD")

  // The ISIN of the instrument a spin-off in these examples is about, and the description of the
  // spin-off, which names it first.
  private val parentIsin = "KYG875721634"
  private val spinoffDescription =
    s"NNND($parentIsin) SPINOFF 1 FOR 10 (NND.SPO, NNN SPINOFF, KYG875721000)"

  // A leg of the action `actionId`, of type `code`, on the contract `conid`, `quantity` signed.
  private def leg(
    id: String,
    actionId: String,
    code: String,
    conid: String,
    quantity: String,
    overrides: (String, String)*
  ): IbkrStatementRow =
    IbkrFakes.row(
      accountId,
      IbkrSection.CorporateAction,
      id,
      reportDate,
      (Map(
        "actionID" -> actionId,
        "type" -> code,
        "conid" -> conid,
        "currency" -> "USD",
        "quantity" -> quantity,
        "dateTime" -> dateTime
      ) ++ overrides).toSeq*
    )

  private def withoutAttribute(row: IbkrStatementRow, attribute: String): IbkrStatementRow =
    row.copy(attributes = row.attributes - attribute)

  private def withAttribute(row: IbkrStatementRow, attribute: (String, String)): IbkrStatementRow =
    row.copy(attributes = row.attributes + attribute)

  // A row of another section that has the ISIN, which is where the parent of a spin-off is found.
  private def holding(id: String, conid: String, isin: String, reported: LocalDate = reportDate) =
    IbkrFakes.row(accountId, IbkrSection.Trade, id, reported, "conid" -> conid, "isin" -> isin)

  private def interpret(
    rows: List[IbkrStatementRow],
    others: List[IbkrStatementRow] = Nil
  ): IbkrCorporateActions.Interpretation =
    IbkrCorporateActions.interpret(rows, others, newYork).toOption.get

  private def operationsOf(rows: List[IbkrStatementRow]): List[AccountOperation] =
    interpret(rows).records.flatMap(_.translations)

  // What the operations are, as (ref, kind, quantity, amount).
  private def shapeOf(
    operations: List[AccountOperation]
  ): List[(OperationRef, OperationKind, BigDecimal, BigDecimal)] =
    operations.map(op => (op.ref, op.kind, op.quantity, op.amount))

  private def summary(
    actionId: String,
    code: Option[String],
    description: Option[String] = None
  ): ActionSummary =
    ActionSummary(actionId, code, actionDay, description)

  private def ref(id: String): OperationRef = OperationRef(s"corporate_action/$id")

  // The legs of a conversion: 100 units of one contract given up for 50 of another.
  private def outAndIn(code: String): List[IbkrStatementRow] =
    List(leg("4001", "A1", code, "111", "-100"), leg("4002", "A1", code, "222", "50"))

  "IbkrCorporateActions" >> {
    "interpret" >> {

      "an action read by its type" >> {

        "a split is the one leg of a forward or reverse split, with no cash" >> {
          val forward = leg("4001", "A1", "FS", "265598", "30")

          interpret(List(forward)).records must_== List(
            IbkrRowRecord(
              forward,
              List(
                AccountOperation(
                  accountId,
                  ref("4001"),
                  OperationKind.Split,
                  OperationState.Executed,
                  occurredAt,
                  Some(instrumentKey("265598")),
                  BigDecimal(30),
                  None,
                  BigDecimal(0),
                  usd,
                  BrokerOperationType("FS"),
                  Some(OperationGroup("A1"))
                )
              )
            )
          )
        }

        "a reverse split keeps its negative quantity" >> {
          shapeOf(operationsOf(List(leg("4001", "A1", "RS", "265598", "-75")))) must_==
            List((ref("4001"), OperationKind.Split, BigDecimal(-75), BigDecimal(0)))
        }

        Fragments.foreach(List("FS", "RS", "IC", "TC", "TO", "SR")) { code =>
          s"$code with legs that gave up units and legs that received them makes each leg a conversion" >> {
            shapeOf(operationsOf(outAndIn(code))) must_== List(
              (ref("4001"), OperationKind.Conversion, BigDecimal(-100), BigDecimal(0)),
              (ref("4002"), OperationKind.Conversion, BigDecimal(50), BigDecimal(0))
            )
          }
        }

        "a conversion leg carries the market value per unit as its price, where IB reports a value" >> {
          val rows = List(
            leg("4001", "A1", "TC", "111", "-100", "value" -> "-2500"),
            leg("4002", "A1", "TC", "222", "50", "value" -> "2000"),
            leg("4003", "A1", "TC", "333", "10")
          )

          operationsOf(rows)
            .map(_.price) must_== List(Some(BigDecimal(25)), Some(BigDecimal(40)), None)
        }

        "a conversion leg carries the cash that IB books on it" >> {
          val rows = List(
            leg("4001", "A1", "TC", "111", "-100", "proceeds" -> "500"),
            leg("4002", "A1", "TC", "222", "50")
          )

          operationsOf(rows).map(_.amount) must_== List(BigDecimal(500), BigDecimal(0))
        }

        Fragments.foreach(List("TC" -> "9999", "DW" -> "0", "TM" -> "1000")) { case (code, cash) =>
          s"$code with legs that gave up units only makes each a sale at its proceeds, of $cash" >> {
            val sale = leg("4001", "A1", code, "111", "-30", "proceeds" -> cash, "value" -> "-9800")

            operationsOf(List(sale)) must_== List(
              AccountOperation(
                accountId,
                ref("4001"),
                OperationKind.Sell,
                OperationState.Executed,
                occurredAt,
                Some(instrumentKey("111")),
                BigDecimal(-30),
                None,
                BigDecimal(cash),
                usd,
                BrokerOperationType(code),
                Some(OperationGroup("A1"))
              )
            )
          }
        }

        "a stock dividend is the leg of shares received, with the market value per unit as its price" >> {
          val dividend = leg("4001", "A1", "SD", "265598", "50", "value" -> "1595")

          operationsOf(List(dividend)) must_== List(
            AccountOperation(
              accountId,
              ref("4001"),
              OperationKind.StockDividend,
              OperationState.Executed,
              occurredAt,
              Some(instrumentKey("265598")),
              BigDecimal(50),
              Some(BigDecimal("31.9")),
              BigDecimal(0),
              usd,
              BrokerOperationType("SD"),
              Some(OperationGroup("A1"))
            )
          )
        }

        Fragments.foreach(List("SO", "RI")) { code =>
          s"$code with legs of shares received only makes each a spin-off, with the parent's marker" >> {
            val child = leg("4001", "A1", code, "444", "5", "description" -> spinoffDescription)
            val parent = holding("9001", "111", parentIsin)

            shapeOf(interpret(List(child), List(parent)).records.flatMap(_.translations)) must_==
              List(
                (ref("4001"), OperationKind.Spinoff, BigDecimal(5), BigDecimal(0)),
                (
                  OperationRef("corporate_action/4001/parent"),
                  OperationKind.Spinoff,
                  BigDecimal(0),
                  BigDecimal(0)
                )
              )
          }
        }

        "the marker of a spin-off names the parent, with the date, the currency and the group of the leg" >> {
          val child = leg("4001", "A1", "SO", "444", "5", "description" -> spinoffDescription)
          val parent = holding("9001", "111", parentIsin)

          interpret(List(child), List(parent)).records.flatMap(_.translations).last must_==
            AccountOperation(
              accountId,
              OperationRef("corporate_action/4001/parent"),
              OperationKind.Spinoff,
              OperationState.Executed,
              occurredAt,
              Some(instrumentKey("111")),
              BigDecimal(0),
              None,
              BigDecimal(0),
              usd,
              BrokerOperationType("SO"),
              Some(OperationGroup("A1"))
            )
        }

        "a leg of no units in a conversion is a conversion leg of no units, so its cash is the conversion's" >> {
          val rows = outAndIn("TC") :+ leg("4003", "A1", "TC", "222", "0", "proceeds" -> "30")

          shapeOf(operationsOf(rows).drop(2)) must_==
            List((ref("4003"), OperationKind.Conversion, BigDecimal(0), BigDecimal(30)))
        }

        "a leg of no units in a sale pays its cash to the leg that gave up units, and keeps none" >> {
          val rows = List(
            leg("4001", "A1", "TC", "111", "-100"),
            leg("4003", "A1", "TC", "111", "0", "proceeds" -> "30")
          )

          shapeOf(operationsOf(rows)) must_== List(
            (ref("4001"), OperationKind.Sell, BigDecimal(-100), BigDecimal(30)),
            (ref("4003"), OperationKind.Other, BigDecimal(0), BigDecimal(0))
          )
        }

        "the cash of a leg of no units in a sale is shared by the legs that gave up units, by their units" >> {
          // 30 shared over 100 and 50 units is 20 and 10, on top of their own 10 and 5.
          val rows = List(
            leg("4001", "A1", "TC", "111", "-100", "proceeds" -> "10"),
            leg("4002", "A1", "TC", "222", "-50", "proceeds" -> "5"),
            leg("4003", "A1", "TC", "111", "0", "proceeds" -> "30")
          )

          operationsOf(rows).map(_.amount) must_==
            List(BigDecimal(30), BigDecimal(15), BigDecimal(0))
        }

        "an action read by its type gives no notice" >> {
          interpret(outAndIn("TC")).notices must beEmpty
        }
      }

      "an action read by the shape of its legs" >> {
        // The ISIN of the instrument the descriptions below say the action is about.
        val isin = "US0000000001"
        val splitDescription = s"AAA($isin) SPLIT 2 FOR 1 (AAA, AAA INC, $isin)"

        "one leg without cash on the instrument its description names is a split" >> {
          val rows =
            List(
              leg(
                "4001",
                "A1",
                "OR",
                "111",
                "30",
                "isin" -> isin,
                "description" -> splitDescription
              )
            )

          val result = interpret(rows)

          result.records.flatMap(_.translations).map(_.kind) must_== List(OperationKind.Split)
          result.notices must_== List(
            Notice
              .ReadByShape(summary("A1", Some("OR"), Some(splitDescription)), OperationKind.Split)
          )
        }

        "legs that gave up units and legs that received them are a conversion" >> {
          val result = interpret(outAndIn("OR"))

          result.records.flatMap(_.translations).map(_.kind) must_==
            List(OperationKind.Conversion, OperationKind.Conversion)
          result.notices must_==
            List(Notice.ReadByShape(summary("A1", Some("OR")), OperationKind.Conversion))
        }

        "legs that gave up units only are sales at their proceeds" >> {
          val rows = List(leg("4001", "A1", "BM", "111", "-1000", "proceeds" -> "1000"))

          val result = interpret(rows)

          shapeOf(result.records.flatMap(_.translations)) must_==
            List((ref("4001"), OperationKind.Sell, BigDecimal(-1000), BigDecimal(1000)))
          result.notices must_== List(
            Notice.ReadByShape(summary("A1", Some("BM")), OperationKind.Sell)
          )
        }

        "legs of shares received only, none on the instrument the description names, are a spin-off" >> {
          val rows =
            List(
              leg(
                "4001",
                "A1",
                "CO",
                "444",
                "5",
                "isin" -> "KYG875721000",
                "description" -> spinoffDescription
              )
            )
          val parent = holding("9001", "111", parentIsin)

          val result = interpret(rows, List(parent))

          result.records.flatMap(_.translations).map(_.kind) must_==
            List(OperationKind.Spinoff, OperationKind.Spinoff)
          result.notices must_== List(
            Notice.ReadByShape(
              summary("A1", Some("CO"), Some(spinoffDescription)),
              OperationKind.Spinoff
            )
          )
        }

        "a leg of shares received on the instrument the description names is not a spin-off" >> {
          val rows = List(
            leg(
              "4001",
              "A1",
              "CO",
              "444",
              "5",
              "isin" -> parentIsin,
              "description" -> spinoffDescription
            ),
            leg("4002", "A1", "CO", "555", "5", "isin" -> "KYG875721999")
          )

          interpret(rows).notices must_== List(
            Notice.NotInterpreted(summary("A1", Some("CO"), Some(spinoffDescription)))
          )
        }

        "a type of the table whose legs fit none of its rows is read by their shape too" >> {
          // A stock dividend is shares received only, but this one also gave up units.
          val result = interpret(outAndIn("SD"))

          result.records.flatMap(_.translations).map(_.kind) must_==
            List(OperationKind.Conversion, OperationKind.Conversion)
          result.notices must_==
            List(Notice.ReadByShape(summary("A1", Some("SD")), OperationKind.Conversion))
        }
      }

      "an action that is not interpreted" >> {

        "keeps a leg that gained units as shares in, with the units and the cash" >> {
          val received = leg("4001", "A1", "XX", "265598", "480", "proceeds" -> "12.5")

          interpret(List(received)).records must_== List(
            IbkrRowRecord(
              received,
              List(
                AccountOperation(
                  accountId,
                  ref("4001"),
                  OperationKind.SecurityIn,
                  OperationState.Executed,
                  occurredAt,
                  Some(instrumentKey("265598")),
                  BigDecimal(480),
                  None,
                  BigDecimal("12.5"),
                  usd,
                  BrokerOperationType("XX"),
                  Some(OperationGroup("A1"))
                )
              )
            )
          )
        }

        "keeps a leg that lost units as shares out, with a negative quantity" >> {
          shapeOf(operationsOf(List(leg("4002", "A1", "XX", "265598", "-12000")))) must_==
            List((ref("4002"), OperationKind.SecurityOut, BigDecimal(-12000), BigDecimal(0)))
        }

        "keeps a leg of no units as other, carrying the cash" >> {
          val cashOnly = leg("4003", "A1", "XX", "-", "0", "proceeds" -> "30")

          shapeOf(operationsOf(List(cashOnly))) must_==
            List((ref("4003"), OperationKind.Other, BigDecimal(0), BigDecimal(30)))
        }

        "tells of it in a notice naming the action, its type, its date and IB's description" >> {
          val received = leg("4001", "A1", "XX", "265598", "5", "description" -> "SOMETHING NEW")

          interpret(List(received)).notices must_== List(
            Notice.NotInterpreted(summary("A1", Some("XX"), Some("SOMETHING NEW")))
          )
        }

        "is an action of a type IB does not document" >> {
          interpret(outAndIn("XX")).records.flatMap(_.translations).map(_.kind) must_==
            List(OperationKind.SecurityOut, OperationKind.SecurityIn)
        }

        "is an action that names no type" >> {
          val rows = outAndIn("TC").map(withoutAttribute(_, "type"))

          val result = interpret(rows)

          result.records.flatMap(_.translations).map(_.kind) must_==
            List(OperationKind.SecurityOut, OperationKind.SecurityIn)
          result.notices must_== List(Notice.NotInterpreted(summary("A1", None)))
        }

        "is an action whose legs disagree about the type" >> {
          val rows =
            List(leg("4001", "A1", "TC", "111", "-100"), leg("4002", "A1", "SO", "222", "50"))

          interpret(rows).notices must_== List(Notice.NotInterpreted(summary("A1", None)))
        }

        "is an action whose cash is in more than one currency" >> {
          val rows = List(
            leg("4001", "A1", "TC", "111", "-100", "proceeds" -> "500"),
            leg("4002", "A1", "TC", "222", "50", "proceeds" -> "20", "currency" -> "EUR")
          )

          val result = interpret(rows)

          result.records.flatMap(_.translations).map(_.kind) must_==
            List(OperationKind.SecurityOut, OperationKind.SecurityIn)
          result.notices must_== List(Notice.NotInterpreted(summary("A1", Some("TC"))))
        }

        "is a sale whose legs of no units carry cash in another currency than the legs that gave up units" >> {
          val rows = List(
            leg("4001", "A1", "TC", "111", "-100"),
            leg("4003", "A1", "TC", "111", "0", "proceeds" -> "30", "currency" -> "EUR")
          )

          interpret(rows).notices must_== List(Notice.NotInterpreted(summary("A1", Some("TC"))))
        }

        "is an action of a documented type that matches no shape" >> {
          // Shares received with cash, which no row of the table and no shape reads.
          val rows = List(leg("4001", "A1", "TC", "222", "50", "proceeds" -> "20"))

          interpret(rows).notices must_== List(Notice.NotInterpreted(summary("A1", Some("TC"))))
        }
      }

      "cancellations" >> {
        val original = leg("4002", "A1", "TC", "222", "50")
        val cancel = leg("4003", "A1", "TC", "222", "-50", "code" -> "Ca")
          .copy(reportDate = reportDate.plusDays(4))

        "a cancelling leg and the leg it cancels are both canceled, with the kind their sign gives" >> {
          val rows = List(leg("4001", "A1", "TC", "111", "-100"), original, cancel)

          interpret(rows).records.map(_.translations.map(op => (op.kind, op.state))) must_== List(
            List((OperationKind.Sell, OperationState.Executed)),
            List((OperationKind.SecurityIn, OperationState.Canceled)),
            List((OperationKind.SecurityOut, OperationState.Canceled))
          )
        }

        "the legs left are read without the ones that were canceled" >> {
          val corrected =
            leg("4004", "A1", "TC", "222", "60").copy(reportDate = reportDate.plusDays(4))
          val rows = List(leg("4001", "A1", "TC", "111", "-100"), original, cancel, corrected)

          interpret(rows).records
            .flatMap(_.translations)
            .filter(_.state == OperationState.Executed)
            .map(op => (op.ref, op.kind, op.quantity)) must_== List(
            (ref("4001"), OperationKind.Conversion, BigDecimal(-100)),
            (ref("4004"), OperationKind.Conversion, BigDecimal(60))
          )
        }

        "a cancelling leg that matches no leg is canceled alone" >> {
          val result = interpret(List(cancel))

          result.records.map(_.translations.map(op => (op.kind, op.state))) must_==
            List(List((OperationKind.SecurityOut, OperationState.Canceled)))
          result.notices must beEmpty
        }

        "a cancelling leg cancels the leg reported last, and another cancelling leg cancels another" >> {
          val first = leg("4001", "A1", "RS", "222", "5")
          val second = leg("4002", "A2", "RS", "222", "5").copy(reportDate = reportDate.plusDays(1))
          val cancelsSecond =
            leg("4003", "A3", "RS", "222", "-5", "code" -> "Ca")
              .copy(reportDate = reportDate.plusDays(2))
          val cancelsFirst =
            leg("4004", "A4", "RS", "222", "-5", "code" -> "Ca")
              .copy(reportDate = reportDate.plusDays(3))

          def statesWithout(rows: List[IbkrStatementRow]) =
            interpret(rows).records.map(_.translations.map(_.state))

          statesWithout(List(first, second, cancelsSecond)) must_== List(
            List(OperationState.Executed),
            List(OperationState.Canceled),
            List(OperationState.Canceled)
          )
          statesWithout(List(first, second, cancelsSecond, cancelsFirst)) must_== List(
            List(OperationState.Canceled),
            List(OperationState.Canceled),
            List(OperationState.Canceled),
            List(OperationState.Canceled)
          )
        }

        "a cancelling leg cancels only a leg of the same instrument and type, with the opposite quantity" >> {
          val target = leg("4001", "A1", "RS", "222", "5")
          val otherInstrument =
            leg("4002", "A2", "RS", "333", "5").copy(reportDate = reportDate.plusDays(1))
          val otherType =
            leg("4003", "A3", "FS", "222", "5").copy(reportDate = reportDate.plusDays(1))
          val otherQuantity =
            leg("4004", "A4", "RS", "222", "6").copy(reportDate = reportDate.plusDays(1))
          val cancelling =
            leg("4005", "A5", "RS", "222", "-5", "code" -> "Ca")
              .copy(reportDate = reportDate.plusDays(2))

          val rows = List(target, otherInstrument, otherType, otherQuantity, cancelling)

          interpret(rows).records.map(_.translations.map(_.state)) must_== List(
            List(OperationState.Canceled),
            List(OperationState.Executed),
            List(OperationState.Executed),
            List(OperationState.Executed),
            List(OperationState.Canceled)
          )
        }

        "a cancelling leg does not cancel a leg reported after it" >> {
          val early = cancel.copy(reportDate = reportDate)
          val later = original.copy(reportDate = reportDate.plusDays(1))

          interpret(List(early, later)).records.map(_.translations.map(_.state)) must_== List(
            List(OperationState.Canceled),
            List(OperationState.Executed)
          )
        }

        "a leg noted with Ca among other codes cancels" >> {
          val noted = withAttribute(cancel, "code" -> "P;Ca")

          interpret(List(original, noted)).records.map(_.translations.map(_.state)) must_== List(
            List(OperationState.Canceled),
            List(OperationState.Canceled)
          )
        }
      }

      "the parent of a spin-off" >> {
        val child = leg("4001", "A1", "SO", "444", "5", "description" -> spinoffDescription)

        // The instrument the marker of the child's spin-off names.
        def parentOf(rows: List[IbkrStatementRow], others: List[IbkrStatementRow]) =
          interpret(rows, others).records
            .flatMap(_.translations)
            .find(_.ref == OperationRef("corporate_action/4001/parent"))
            .flatMap(_.instrument)

        "is the instrument of a row that has the ISIN the description names" >> {
          parentOf(List(child), List(holding("9001", "111", parentIsin))) must beSome(
            instrumentKey("111")
          )
        }

        "is the one of the row reported last when several have the ISIN" >> {
          val rows = List(
            holding("9001", "111", parentIsin, reportDate.minusDays(5)),
            holding("9002", "222", parentIsin, reportDate.minusDays(1)),
            holding("9003", "333", parentIsin, reportDate.minusDays(9))
          )

          parentOf(List(child), rows) must beSome(instrumentKey("222"))
        }

        "is the one with the later transaction id when they are reported on one day" >> {
          // As numbers, 100 is later than 99, though the text "100" sorts before "99".
          val rows = List(holding("99", "111", parentIsin), holding("100", "222", parentIsin))

          parentOf(List(child), rows) must beSome(instrumentKey("222"))
        }

        "can be a leg of another action" >> {
          val earlier = leg("3001", "A0", "TC", "111", "10", "isin" -> parentIsin)

          parentOf(List(child, earlier), Nil) must beSome(instrumentKey("111"))
        }

        "is not looked for among the legs of the action itself" >> {
          val ownLeg = withAttribute(child, "isin" -> parentIsin)

          interpret(List(ownLeg)).notices must_== List(
            Notice.SpinOffWithoutParent(summary("A1", Some("SO"), Some(spinoffDescription)))
          )
        }

        "is named by the first ISIN in parentheses of the description" >> {
          val twoIsins =
            withAttribute(child, "description" -> s"NNND($parentIsin) SPINOFF OF OLD(US0000000001)")
          val rows = List(
            holding("9001", "111", parentIsin),
            holding("9002", "222", "US0000000001", reportDate.plusDays(3))
          )

          parentOf(List(twoIsins), rows) must beSome(instrumentKey("111"))
        }

        "is not named by an ISIN outside parentheses" >> {
          val bare = withAttribute(child, "description" -> s"SPINOFF OF NNND $parentIsin")

          interpret(List(bare), List(holding("9001", "111", parentIsin))).notices must_== List(
            Notice
              .SpinOffWithoutParent(summary("A1", Some("SO"), Some(s"SPINOFF OF NNND $parentIsin")))
          )
        }

        "that is missing is told of in a notice, and the spin-off has no marker" >> {
          val result = interpret(List(child))

          result.records.flatMap(_.translations).map(_.ref) must_== List(ref("4001"))
          result.notices must_== List(
            Notice.SpinOffWithoutParent(summary("A1", Some("SO"), Some(spinoffDescription)))
          )
        }

        "that is missing is told of once for an action with several new instruments" >> {
          val second = leg("4002", "A1", "SO", "555", "7", "description" -> spinoffDescription)

          interpret(List(child, second)).notices.size must_== 1
        }
      }

      "a stock dividend without a value" >> {
        val dividend = leg("4001", "A1", "SD", "265598", "50")

        "has no price, and is told of in a notice" >> {
          val result = interpret(List(dividend))

          result.records.flatMap(_.translations).map(op => (op.kind, op.price)) must_==
            List((OperationKind.StockDividend, None))
          result.notices must_== List(Notice.StockDividendWithoutValue(summary("A1", Some("SD"))))
        }

        "is one with a value of zero too" >> {
          interpret(List(withAttribute(dividend, "value" -> "0"))).notices must_==
            List(Notice.StockDividendWithoutValue(summary("A1", Some("SD"))))
        }
      }

      "the group" >> {
        "is the action id, on every operation of every leg of the action" >> {
          val rows = List(
            leg("4001", "A1", "SO", "444", "5", "description" -> spinoffDescription),
            leg("4002", "A1", "SO", "555", "7"),
            leg("4003", "A2", "XX", "666", "3"),
            leg("4004", "A2", "XX", "666", "-3", "code" -> "Ca")
          )

          interpret(rows, List(holding("9001", "111", parentIsin))).records
            .map(_.translations.map(_.group).distinct) must_== List(
            List(Some(OperationGroup("A1"))),
            List(Some(OperationGroup("A1"))),
            List(Some(OperationGroup("A2"))),
            List(Some(OperationGroup("A2")))
          )
        }

        "is none for a leg that names no action, which is an action of its own" >> {
          // Two legs that were one action would fit no shape of a split.
          val rows = List(
            withoutAttribute(leg("4001", "A1", "FS", "111", "10"), "actionID"),
            withoutAttribute(leg("4002", "A1", "FS", "222", "20"), "actionID")
          )

          val result = interpret(rows)

          result.records.flatMap(_.translations).map(op => (op.kind, op.group)) must_==
            List((OperationKind.Split, None), (OperationKind.Split, None))
        }
      }

      "the records" >> {
        "come in the order the legs were given" >> {
          val rows = List(
            leg("4003", "A2", "XX", "666", "3"),
            leg("4001", "A1", "FS", "111", "10"),
            leg("4002", "A0", "RS", "222", "-5")
          )

          interpret(rows).records
            .map(_.row.transactionId.value) must_== List("4003", "4001", "4002")
        }

        "are none for no legs" >> {
          interpret(Nil) must_== IbkrCorporateActions.Interpretation(Nil, Nil)
        }
      }

      "legs that cannot be read" >> {
        "fail when a leg has no currency" >> {
          val noCurrency = withoutAttribute(leg("4001", "A1", "FS", "111", "10"), "currency")

          IbkrCorporateActions.interpret(List(noCurrency), Nil, newYork) must_== Left(
            IbkrStatementError
              .MissingAttribute(
                IbkrSection.CorporateAction,
                Some(IbkrTransactionId("4001")),
                "currency"
              )
          )
        }

        "fail when a leg that has units names no instrument" >> {
          val noInstrument = withoutAttribute(leg("4001", "A1", "FS", "111", "10"), "conid")

          IbkrCorporateActions.interpret(List(noInstrument), Nil, newYork) must_== Left(
            IbkrStatementError
              .MissingAttribute(
                IbkrSection.CorporateAction,
                Some(IbkrTransactionId("4001")),
                "conid"
              )
          )
        }

        "fail when a number is not a number" >> {
          val bad = leg("4001", "A1", "FS", "111", "ten")

          IbkrCorporateActions.interpret(List(bad), Nil, newYork) must_== Left(
            IbkrStatementError.InvalidAttribute(
              IbkrSection.CorporateAction,
              Some(IbkrTransactionId("4001")),
              "quantity",
              "ten"
            )
          )
        }
      }
    }

    "isinsNamed" >> {
      "are the first ISINs in parentheses of the descriptions of the rows" >> {
        val rows = List(
          leg("4001", "A1", "SO", "444", "5", "description" -> spinoffDescription),
          leg("4002", "A2", "SO", "555", "5", "description" -> "BBB(US0000000001) SPINOFF"),
          leg("4003", "A3", "SO", "666", "5", "description" -> "NO ISIN HERE")
        )

        IbkrCorporateActions.isinsNamed(rows) must_== Set(Isin(parentIsin), Isin("US0000000001"))
      }
    }
  }
}
