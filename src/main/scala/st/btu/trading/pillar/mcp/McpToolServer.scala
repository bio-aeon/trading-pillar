package st.btu.trading.pillar.mcp

import cats.Applicative
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.{McpServer, RequestContext, ToolFunction}

/** An MCP server in the effect `G` that offers a fixed list of tools and nothing else.
  *
  * @param info
  *   the name and version clients see
  * @param instructionText
  *   guidance for the model on using the tools, if any
  * @param toolFunctions
  *   the tools, offered in this order for every caller: build the list deterministically, for
  *   instance by concatenating the groups of [[McpTools]] in a fixed order. Names must be unique.
  */
final class McpToolServer[G[_]](
  info: Implementation,
  instructionText: Option[String],
  toolFunctions: List[ToolFunction[G]]
)(implicit G: Applicative[G])
    extends McpServer[G]
    with McpServer.ToolProvider[G] {

  def serverInfo: Implementation = info

  def instructions(context: RequestContext[G]): G[Option[String]] = G.pure(instructionText)

  def tools(context: RequestContext[G]): G[List[ToolFunction[G]]] = G.pure(toolFunctions)
}
