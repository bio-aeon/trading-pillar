package st.btu.trading.pillar.gateways.ibkr

import org.specs2.mutable.Specification

class FlexResponseSpec extends Specification {

  private val referenceCode = "5544332211"

  "FlexResponse" >> {
    "parse" >> {
      "reads the reference code of an accepted request" >> {
        val body =
          s"""<FlexStatementResponse timestamp="09 November, 2026 10:00 AM EST">
             |<Status>Success</Status><ReferenceCode>$referenceCode</ReferenceCode>
             |<Url>https://example.invalid/GetStatement</Url>
             |</FlexStatementResponse>""".stripMargin

        FlexResponse.parse(body) must beRight[FlexResponse](FlexResponse.Accepted(referenceCode))
      }

      "reads the error code and message of a refusal, whatever the status" >> {
        val body =
          """<FlexStatementResponse><Status>Warn</Status><ErrorCode>1019</ErrorCode>
            |<ErrorMessage>Statement generation in progress.</ErrorMessage>
            |</FlexStatementResponse>""".stripMargin

        FlexResponse.parse(body) must beRight[FlexResponse](
          FlexResponse.Refused(1019, "Statement generation in progress.")
        )
      }

      "takes a response that is a query response for the statement" >> {
        val body =
          """<FlexQueryResponse queryName="Activity"><FlexStatements count="0"/></FlexQueryResponse>"""

        FlexResponse.parse(body) must beRight[FlexResponse](FlexResponse.Statement(body))
      }
    }

    "an accepted request" >> {
      "does not print its reference code" >> {
        FlexResponse.Accepted(referenceCode).toString must not(contain(referenceCode))
      }
    }

    "a statement" >> {
      "does not print its content" >> {
        FlexResponse
          .Statement("<FlexQueryResponse>account U1234567</FlexQueryResponse>")
          .toString must
          not(contain("U1234567"))
      }
    }
  }
}
