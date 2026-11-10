package st.btu.trading.pillar.gateways.ibkr

import st.btu.trading.pillar.models.ibkr.FlexQueryId

import java.time.LocalDate

/** Reads the statements of Activity Flex Queries from Interactive Brokers' Flex Web Service. */
trait FlexWebService[F[_]] {

  /** The XML of the statement that the Activity Flex Query `queryId` produces for the report dates
    * from `from` to `to`, both inclusive.
    *
    * The service generates a statement in two steps, and an answer to either can ask the caller to
    * try again; this call waits and tries again, up to a limit. It does the same when a call gets
    * no answer, or an HTTP status that asks for a later try (429 or 5xx).
    *
    * @return
    *   a failed `F` with a [[FlexError]] when IB refuses the request, keeps asking to try again,
    *   cannot be reached, or answers with something else than the documented responses
    */
  def statement(queryId: FlexQueryId, from: LocalDate, to: LocalDate): F[String]
}
