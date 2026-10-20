package st.btu.trading.pillar.mcp

import com.comcast.ip4s.{Host, Port}

/** Where the MCP HTTP server listens and who may use it.
  *
  * The server speaks plain HTTP: put it behind a reverse proxy that terminates TLS, or bind it to a
  * loopback address.
  *
  * @param token
  *   the bearer token every request must present; a blank token admits nobody
  * @param allowedOrigins
  *   the browser origins, written `scheme://host[:port]`, allowed to call the server. A request
  *   without an `Origin` header comes from a program rather than a browser and is not checked
  *   against this set, so an empty set refuses every browser and no agent.
  */
final case class McpHttpConfig(
  host: Host,
  port: Port,
  token: AccessToken,
  allowedOrigins: Set[String]
)
