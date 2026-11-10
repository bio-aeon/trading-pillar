package st.btu.trading.pillar.gateways.ibkr

import scala.concurrent.duration.*

/** How [[FlexWebServiceImpl]] paces its calls and waits for a statement.
  *
  * @param minSpacing
  *   the least time between two calls made with one token. IB allows one request per second and ten
  *   per minute per token, which six seconds stay within.
  * @param retryDelay
  *   how long to wait before trying again after an answer that asks for it, such as one saying the
  *   statement is still being generated, and after a call that got no answer
  * @param rateLimitDelay
  *   how long to wait before trying again after IB reports the rate limit exceeded
  * @param maxAttempts
  *   how many times one step is called, the first call included, before it is given up
  */
final case class FlexWebServiceConfig(
  minSpacing: FiniteDuration = 6.seconds,
  retryDelay: FiniteDuration = 10.seconds,
  rateLimitDelay: FiniteDuration = 1.minute,
  maxAttempts: Int = 10
) {
  require(maxAttempts >= 1, "maxAttempts must be at least 1")
}
