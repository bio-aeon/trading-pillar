package st.btu.trading.pillar.gateways.ibkr

/** Why a read through the Flex Web Service failed. */
sealed abstract class FlexError(message: String) extends Exception(message)

object FlexError {

  /** The code IB answers when it cannot generate a statement at the moment. It also answers it for
    * a window of report dates it finds too long, so a caller can read a shorter window instead.
    */
  val StatementNotGenerated: Int = 1001

  /** IB refused the request for a reason that waiting does not change, such as an expired token, an
    * invalid token or an invalid query.
    *
    * @param code
    *   IB's numeric error code
    * @param reason
    *   IB's error message
    */
  final case class Permanent(code: Int, reason: String)
      extends FlexError(s"The Flex Web Service refused the request ($code): $reason")

  /** IB kept answering that the request should be tried again until the attempts ran out.
    *
    * @param code
    *   IB's numeric error code of the last answer
    * @param reason
    *   IB's error message of the last answer
    * @param attempts
    *   how many times the call was made
    */
  final case class RetriesExhausted(code: Int, reason: String, attempts: Int)
      extends FlexError(
        s"The Flex Web Service still answered $code after $attempts attempts: $reason"
      )

  /** The answer was none of those the service documents, no answer came as an XML body, or the HTTP
    * status was a failure that waiting does not change, such as 403 or 404.
    */
  final case class UnexpectedResponse(detail: String)
      extends FlexError(s"Unexpected response of the Flex Web Service: $detail")

  /** No usable answer came: the call failed on the way, by a refused connection, a timeout or a
    * dropped connection, or the service answered with an HTTP status that asks for the call to be
    * made again later (429 or 5xx).
    *
    * @param detail
    *   what failed, built from the cause of the failure and never from the request, whose URL
    *   carries the token
    */
  final case class Unreachable(detail: String)
      extends FlexError(s"The Flex Web Service could not be reached: $detail")
}
