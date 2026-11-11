package st.btu.trading.pillar.gateways.ibkr

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{BrokerInstrumentId, Currency}
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.market.{ClassCode, Ticker}

import java.time.LocalDate

class IbkrStatementParserSpec extends Specification {

  // The response of a query with `body` as the content of one statement of U1111111.
  private def response(body: String): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<FlexQueryResponse queryName="Activity" type="AF">
       |  <FlexStatements count="1">
       |    <FlexStatement accountId="U1111111" fromDate="20260101" toDate="20260131" period="Custom" whenGenerated="20260201;061500">
       |      $body
       |    </FlexStatement>
       |  </FlexStatements>
       |</FlexQueryResponse>""".stripMargin

  private def statementOf(body: String): Either[IbkrStatementError, IbkrStatement] =
    IbkrStatementParser.parse(response(body)).map(_.head)

  private def rowsOf(body: String): List[IbkrReportedRow] =
    statementOf(body).toOption.get.rows

  private def ids(rows: List[IbkrReportedRow]): List[(IbkrSection, IbkrTransactionId)] =
    rows.map(row => (row.section, row.transactionId))

  private val execution =
    """<Trade accountId="U1111111" assetCategory="STK" symbol="AAPL" conid="265598" currency="USD"
      |  buySell="BUY" quantity="10" tradePrice="150.25" proceeds="-1,502.50" ibCommission="-1"
      |  ibCommissionCurrency="USD" transactionID="1001" reportDate="20260105"
      |  dateTime="20260105;093000" levelOfDetail="EXECUTION"/>""".stripMargin

  "IbkrStatementParser" >> {
    "parse" >> {

      "statements" >> {
        "returns the account and the dates of a statement" >> {
          statementOf("") must beRight[IbkrStatement].like { case statement =>
            statement.ibkrAccountId must_== IbkrAccountId("U1111111")
            statement.fromDate must_== LocalDate.of(2026, 1, 1)
            statement.toDate must_== LocalDate.of(2026, 1, 31)
          }
        }

        "returns one statement for each account of a response, with its own rows" >> {
          val xml =
            """<FlexQueryResponse queryName="Activity" type="AF">
              |  <FlexStatements count="2">
              |    <FlexStatement accountId="U1111111" fromDate="20260101" toDate="20260131">
              |      <Trades>
              |        <Trade transactionID="1001" reportDate="20260105" levelOfDetail="EXECUTION"/>
              |      </Trades>
              |    </FlexStatement>
              |    <FlexStatement accountId="U2222222" fromDate="20260101" toDate="20260131">
              |      <Trades>
              |        <Trade transactionID="9001" reportDate="20260106" levelOfDetail="EXECUTION"/>
              |      </Trades>
              |    </FlexStatement>
              |  </FlexStatements>
              |</FlexQueryResponse>""".stripMargin

          IbkrStatementParser.parse(xml).map(_.map(s => (s.ibkrAccountId, ids(s.rows)))) must_==
            Right(
              List(
                (IbkrAccountId("U1111111"), List((IbkrSection.Trade, IbkrTransactionId("1001")))),
                (IbkrAccountId("U2222222"), List((IbkrSection.Trade, IbkrTransactionId("9001"))))
              )
            )
        }

        "returns no statement for a response without one" >> {
          val xml =
            """<FlexQueryResponse queryName="Activity" type="AF"><FlexStatements count="0"/></FlexQueryResponse>"""

          IbkrStatementParser.parse(xml) must beRight[List[IbkrStatement]](List.empty)
        }

        "fails on a statement without an account" >> {
          val xml =
            """<FlexQueryResponse><FlexStatements count="1">
              |<FlexStatement fromDate="20260101" toDate="20260131"/>
              |</FlexStatements></FlexQueryResponse>""".stripMargin

          IbkrStatementParser.parse(xml) must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }

        "fails on a statement whose date is not a date" >> {
          val xml =
            """<FlexQueryResponse><FlexStatements count="1">
              |<FlexStatement accountId="U1111111" fromDate="2026-01-01" toDate="20260131"/>
              |</FlexStatements></FlexQueryResponse>""".stripMargin

          IbkrStatementParser.parse(xml) must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }
      }

      "trades" >> {
        "keeps a trade at the execution level with every attribute as sent" >> {
          rowsOf(s"<Trades>$execution</Trades>") must beLike[List[IbkrReportedRow]] {
            case List(row) =>
              row.section must_== IbkrSection.Trade
              row.transactionId must_== IbkrTransactionId("1001")
              row.reportDate must_== LocalDate.of(2026, 1, 5)
              row.attributes must_== Map(
                "accountId" -> "U1111111",
                "assetCategory" -> "STK",
                "symbol" -> "AAPL",
                "conid" -> "265598",
                "currency" -> "USD",
                "buySell" -> "BUY",
                "quantity" -> "10",
                "tradePrice" -> "150.25",
                "proceeds" -> "-1,502.50",
                "ibCommission" -> "-1",
                "ibCommissionCurrency" -> "USD",
                "transactionID" -> "1001",
                "reportDate" -> "20260105",
                "dateTime" -> "20260105;093000",
                "levelOfDetail" -> "EXECUTION"
              )
          }
        }

        "leaves out the orders and summaries that IB reports for the same fills" >> {
          val body =
            s"""<Trades>
               |  $execution
               |  <Order accountId="U1111111" symbol="AAPL" conid="265598" quantity="10" transactionID="1001" reportDate="20260105" levelOfDetail="ORDER"/>
               |  <SymbolSummary accountId="U1111111" symbol="AAPL" quantity="10" transactionID="1001" reportDate="20260105" levelOfDetail="SYMBOL_SUMMARY"/>
               |  <AssetSummary accountId="U1111111" assetCategory="STK" quantity="10" transactionID="1001" reportDate="20260105" levelOfDetail="ASSET_SUMMARY"/>
               |</Trades>""".stripMargin

          ids(rowsOf(body)) must_== List((IbkrSection.Trade, IbkrTransactionId("1001")))
        }

        "leaves out a trade at another level of detail" >> {
          val body =
            """<Trades>
              |  <Trade transactionID="1001" reportDate="20260105" levelOfDetail="EXECUTION"/>
              |  <Trade transactionID="1002" reportDate="20260105" levelOfDetail="CLOSED_LOT"/>
              |</Trades>""".stripMargin

          ids(rowsOf(body)) must_== List((IbkrSection.Trade, IbkrTransactionId("1001")))
        }

        "keeps a trade that names no level of detail" >> {
          val body = """<Trades><Trade transactionID="1001" reportDate="20260105"/></Trades>"""

          ids(rowsOf(body)) must_== List((IbkrSection.Trade, IbkrTransactionId("1001")))
        }

        "fails on a trade without a transaction id" >> {
          val body = """<Trades><Trade reportDate="20260105" levelOfDetail="EXECUTION"/></Trades>"""

          statementOf(body) must beLeft[IbkrStatementError](
            IbkrStatementError.MissingAttribute(IbkrSection.Trade, None, "transactionID")
          )
        }
      }

      "cash transactions" >> {
        "keeps a detail row" >> {
          val body =
            """<CashTransactions>
              |  <CashTransaction accountId="U1111111" type="Dividends" amount="12.50" currency="USD"
              |    transactionID="2001" reportDate="20260110" levelOfDetail="DETAIL"/>
              |</CashTransactions>""".stripMargin

          ids(rowsOf(body)) must_== List((IbkrSection.CashTransaction, IbkrTransactionId("2001")))
        }

        "leaves out a summary row, which repeats a detail row under account -" >> {
          val body =
            """<CashTransactions>
              |  <CashTransaction accountId="U1111111" type="Dividends" amount="12.50" currency="USD"
              |    transactionID="2001" reportDate="20260110" levelOfDetail="DETAIL"/>
              |  <CashTransaction accountId="-" type="Dividends" amount="12.50" currency="USD"
              |    reportDate="20260110" levelOfDetail="SUMMARY"/>
              |</CashTransactions>""".stripMargin

          ids(rowsOf(body)) must_== List((IbkrSection.CashTransaction, IbkrTransactionId("2001")))
        }

        "leaves out a row that names no level and has no transaction id" >> {
          val body =
            """<CashTransactions>
              |  <CashTransaction type="Dividends" amount="12.50" currency="USD" reportDate="20260110"/>
              |</CashTransactions>""".stripMargin

          rowsOf(body) must beEmpty
        }
      }

      "transfers" >> {
        "keeps a transfer" >> {
          val body =
            """<Transfers>
              |  <Transfer accountId="U1111111" type="ACATS" direction="IN" assetCategory="STK"
              |    symbol="MSFT" conid="272093" quantity="5" transactionID="3001" reportDate="20260115"/>
              |</Transfers>""".stripMargin

          ids(rowsOf(body)) must_== List((IbkrSection.Transfer, IbkrTransactionId("3001")))
        }

        "fails on a transfer without a transaction id" >> {
          val body = """<Transfers><Transfer type="ACATS" reportDate="20260115"/></Transfers>"""

          statementOf(body) must beLeft[IbkrStatementError](
            IbkrStatementError.MissingAttribute(IbkrSection.Transfer, None, "transactionID")
          )
        }
      }

      "corporate actions" >> {
        "keeps the legs at the detail level and leaves out the summary" >> {
          val body =
            """<CorporateActions>
              |  <CorporateAction type="RS" quantity="-12000" conid="1" transactionID="4001"
              |    reportDate="20260120" levelOfDetail="DETAIL"/>
              |  <CorporateAction type="RS" quantity="480" conid="2" transactionID="4002"
              |    reportDate="20260120" levelOfDetail="DETAIL"/>
              |  <CorporateAction type="RS" quantity="-12000" reportDate="20260120"
              |    levelOfDetail="SUMMARY"/>
              |</CorporateActions>""".stripMargin

          ids(rowsOf(body)) must_== List(
            (IbkrSection.CorporateAction, IbkrTransactionId("4001")),
            (IbkrSection.CorporateAction, IbkrTransactionId("4002"))
          )
        }
      }

      "rows" >> {
        "keep the order the statement lists them in, across sections" >> {
          val body =
            s"""<CashTransactions>
               |  <CashTransaction transactionID="2001" reportDate="20260110" levelOfDetail="DETAIL"/>
               |</CashTransactions>
               |<Trades>$execution</Trades>""".stripMargin

          ids(rowsOf(body)) must_== List(
            (IbkrSection.CashTransaction, IbkrTransactionId("2001")),
            (IbkrSection.Trade, IbkrTransactionId("1001"))
          )
        }

        "fail when a row has no report date" >> {
          val body = """<Trades><Trade transactionID="1001" levelOfDetail="EXECUTION"/></Trades>"""

          statementOf(body) must beLeft[IbkrStatementError](
            IbkrStatementError
              .MissingAttribute(IbkrSection.Trade, Some(IbkrTransactionId("1001")), "reportDate")
          )
        }

        "fail when the report date of a row is not a date" >> {
          val body =
            """<Trades><Trade transactionID="1001" reportDate="05/01/2026" levelOfDetail="EXECUTION"/></Trades>"""

          statementOf(body) must beLeft[IbkrStatementError](
            IbkrStatementError.InvalidAttribute(
              IbkrSection.Trade,
              Some(IbkrTransactionId("1001")),
              "reportDate",
              "05/01/2026"
            )
          )
        }

        "read a placeholder for no value as no value" >> {
          val body =
            """<Trades>
              |  <Trade transactionID="1001" reportDate="N/A" levelOfDetail="EXECUTION"/>
              |</Trades>""".stripMargin

          statementOf(body) must beLeft[IbkrStatementError](
            IbkrStatementError
              .MissingAttribute(IbkrSection.Trade, Some(IbkrTransactionId("1001")), "reportDate")
          )
        }
      }

      "instruments" >> {
        "are read from the Financial Instrument Information section" >> {
          val body =
            """<SecuritiesInfo>
              |  <SecurityInfo assetCategory="STK" symbol="VOD" description="VODAFONE GROUP PLC"
              |    conid="12345" isin="GB00BH4HKS39" listingExchange="LSE" currency="GBP"/>
              |</SecuritiesInfo>""".stripMargin

          statementOf(body).map(_.securities) must beRight[List[IbkrSecurityInfo]](
            List(
              IbkrSecurityInfo(
                BrokerInstrumentId("12345"),
                Ticker("VOD"),
                Some(ClassCode("LSE")),
                Some("GB00BH4HKS39"),
                Some("VODAFONE GROUP PLC"),
                Some("STK"),
                Some(Currency("GBP"))
              )
            )
          )
        }

        "have no value for an attribute that IB sends as -, --, N/A or empty" >> {
          val body =
            """<SecuritiesInfo>
              |  <SecurityInfo assetCategory="STK" symbol="VOD" description="--" conid="12345"
              |    isin="N/A" listingExchange="" currency="-"/>
              |</SecuritiesInfo>""".stripMargin

          statementOf(body).map(_.securities) must beRight[List[IbkrSecurityInfo]](
            List(
              IbkrSecurityInfo(
                BrokerInstrumentId("12345"),
                Ticker("VOD"),
                None,
                None,
                None,
                Some("STK"),
                None
              )
            )
          )
        }

        "are left out when they have no contract id or no symbol" >> {
          val body =
            """<SecuritiesInfo>
              |  <SecurityInfo assetCategory="STK" symbol="AAA" conid="-" currency="USD"/>
              |  <SecurityInfo assetCategory="STK" conid="2" currency="USD"/>
              |  <SecurityInfo assetCategory="STK" symbol="CCC" conid="3" currency="USD"/>
              |</SecuritiesInfo>""".stripMargin

          statementOf(body).map(_.securities.map(_.symbol)) must beRight[List[Ticker]](
            List(Ticker("CCC"))
          )
        }
      }

      "open positions" >> {
        "keep the contract, symbol, asset category and quantity of a summary row" >> {
          val body =
            """<OpenPositions>
              |  <OpenPosition accountId="U1111111" assetCategory="STK" symbol="VOD" conid="12345"
              |    position="1250" levelOfDetail="SUMMARY" reportDate="20260131"/>
              |</OpenPositions>""".stripMargin

          statementOf(body).map(_.openPositions) must beRight[List[IbkrOpenPosition]](
            List(
              IbkrOpenPosition(
                BrokerInstrumentId("12345"),
                Some(Ticker("VOD")),
                Some("STK"),
                BigDecimal(1250)
              )
            )
          )
        }

        "keep a row that names no level of detail" >> {
          val body =
            """<OpenPositions>
              |  <OpenPosition assetCategory="STK" symbol="VOD" conid="12345" position="-30"/>
              |</OpenPositions>""".stripMargin

          statementOf(body).map(_.openPositions.map(_.quantity)) must
            beRight[List[BigDecimal]](List(BigDecimal(-30)))
        }

        "leave out a row at the level of a lot" >> {
          val body =
            """<OpenPositions>
              |  <OpenPosition assetCategory="STK" symbol="VOD" conid="12345" position="1250"
              |    levelOfDetail="SUMMARY"/>
              |  <OpenPosition assetCategory="STK" symbol="VOD" conid="12345" position="1000"
              |    levelOfDetail="LOT"/>
              |</OpenPositions>""".stripMargin

          statementOf(body).map(_.openPositions.map(_.quantity)) must
            beRight[List[BigDecimal]](List(BigDecimal(1250)))
        }

        "leave out a row with no contract id or no quantity" >> {
          val body =
            """<OpenPositions>
              |  <OpenPosition assetCategory="STK" symbol="AAA" position="10" levelOfDetail="SUMMARY"/>
              |  <OpenPosition assetCategory="STK" symbol="BBB" conid="2" levelOfDetail="SUMMARY"/>
              |  <OpenPosition assetCategory="STK" symbol="CCC" conid="3" position="-" levelOfDetail="SUMMARY"/>
              |  <OpenPosition assetCategory="STK" symbol="DDD" conid="4" position="7" levelOfDetail="SUMMARY"/>
              |</OpenPositions>""".stripMargin

          statementOf(body).map(_.openPositions.map(_.symbol)) must
            beRight[List[Option[Ticker]]](List(Some(Ticker("DDD"))))
        }

        "have no symbol or asset category when IB sends a placeholder for it" >> {
          val body =
            """<OpenPositions>
              |  <OpenPosition assetCategory="N/A" symbol="-" conid="12345" position="10"
              |    levelOfDetail="SUMMARY"/>
              |</OpenPositions>""".stripMargin

          statementOf(body).map(_.openPositions) must beRight[List[IbkrOpenPosition]](
            List(IbkrOpenPosition(BrokerInstrumentId("12345"), None, None, BigDecimal(10)))
          )
        }
      }

      "unknown content" >> {
        "is ignored, whether an element or an attribute" >> {
          val body =
            """<CashReport><CashReportCurrency currency="USD" endingCash="5"/></CashReport>
              |<Trades>
              |  <Trade transactionID="1001" reportDate="20260105" levelOfDetail="EXECUTION" futureField="x"/>
              |</Trades>""".stripMargin

          ids(rowsOf(body)) must_== List((IbkrSection.Trade, IbkrTransactionId("1001")))
        }
      }

      "input that is not a statement" >> {
        "fails when the text is not XML" >> {
          IbkrStatementParser.parse("this is not xml") must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }

        "fails when the text is empty" >> {
          IbkrStatementParser.parse("") must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }

        "fails on the response of the service to a request, which is not a statement" >> {
          val xml =
            """<FlexStatementResponse><Status>Fail</Status><ErrorCode>1012</ErrorCode></FlexStatementResponse>"""

          IbkrStatementParser.parse(xml) must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }

        "fails on a document whose type declaration defines an entity, without expanding it" >> {
          val xml =
            """<?xml version="1.0"?>
              |<!DOCTYPE FlexQueryResponse [<!ENTITY account "U1111111">]>
              |<FlexQueryResponse><FlexStatements count="1">
              |<FlexStatement accountId="&account;" fromDate="20260101" toDate="20260131"/>
              |</FlexStatements></FlexQueryResponse>""".stripMargin

          IbkrStatementParser.parse(xml) must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }

        "fails on a document that declares an external entity, without reading it" >> {
          val xml =
            """<?xml version="1.0"?>
              |<!DOCTYPE FlexQueryResponse [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
              |<FlexQueryResponse><FlexStatements count="1">
              |<FlexStatement accountId="&secret;" fromDate="20260101" toDate="20260131"/>
              |</FlexStatements></FlexQueryResponse>""".stripMargin

          IbkrStatementParser.parse(xml) must beLeft[IbkrStatementError].like {
            case IbkrStatementError.Malformed(_) => ok
          }
        }
      }
    }
  }
}
