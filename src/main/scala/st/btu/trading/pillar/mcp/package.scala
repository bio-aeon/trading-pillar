package st.btu.trading.pillar

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

package object mcp {

  // The wrapper prints as a placeholder: an opaque type directly over String would print the
  // secret wherever it is interpolated or a case class holding it is rendered.
  private final class Secret(value: String) {
    val bytes: Array[Byte] = value.getBytes(StandardCharsets.UTF_8)
    val blank: Boolean = value.isBlank

    override def toString: String = "AccessToken(<redacted>)"
  }

  /** The secret a caller presents as `Authorization: Bearer <token>` to use the MCP HTTP server.
    *
    * The value cannot be read back, and the type has no `Show` and no `Loggable`, so it does not
    * reach a log line by accident.
    */
  opaque type AccessToken = Secret

  object AccessToken {

    /** The token with the given secret `value`. A blank value admits no caller. */
    def apply(value: String): AccessToken = new Secret(value)

    extension (token: AccessToken) {

      // Compared in constant time: the time taken depends on the length of the presented value
      // only, never on how much of it matches.
      private[mcp] def admits(presented: String): Boolean =
        !token.blank &&
          MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token.bytes)
    }
  }
}
