# MCP server for AI agents

Package `st.btu.trading.pillar.mcp`, built on the building blocks in `services.commands`
(`CommandEffect`, `CommandHandlers`, `CommandRunner`) and `gateways` (`CapturingTgGateway`,
`CapturedReply`). It lets an AI agent that speaks the
[Model Context Protocol](https://modelcontextprotocol.io) inspect and operate a bot over HTTP: five
tools that call the bot's storages, market data sources and the same command handlers the Telegram
chat uses.

The server is [scala-effect-mcp](https://github.com/linkyard/scala-effect-mcp) 0.4.1. It serves
protocol revision 2026-07-28 and, through sessions, the two earlier revisions. The library only
provides the pieces: the consumer builds the ports, decides which tool groups to serve, and starts
the server. The module adds `mcp-server`, `mcp-server-http4s` and `http4s-ember-server` (http4s
0.23.38) to the classpath, which brings fs2 3.14, cats-effect 3.7 and circe 0.14.16.

```
agent --HTTP /mcp--> McpHttpServer (bearer token, origins)
                       --> McpToolServer --> McpTools
                                               |-- ticker_status, live_quote: storages and sources
                                               `-- bot_query, bot_backtest, bot_command:
                                                     CommandRunner --> command handlers --> CapturingTgGateway
```

## Tools

`McpTools` offers the tools in three groups, so that what a caller may do is the consumer's choice.
Each tool declares its effect to clients, which show it to the model and to the user.

| Group | Tool | Declared effect | Arguments | What it does |
|---|---|---|---|---|
| `readOnly` | `ticker_status` | read-only | `ticker` | Reports how the bot serves a ticker. |
| `readOnly` | `live_quote` | read-only, open world | `ticker` | Fetches the latest quote from the ticker's market data source. |
| `readOnly` | `bot_query` | read-only, open world | `command` | Runs a command line that [`CommandEffect`](#which-lines-bot_query-runs) classifies as read-only. |
| `additive` | `bot_backtest` | additive, not idempotent | `command` | Runs a `/backtest` line and so saves a backtest run. |
| `changing` | `bot_command` | destructive, not idempotent, open world | `command` | Runs any command line, including the ones that change the bot. |

`live_quote`, `bot_query` and `bot_command` are declared open world because they can reach Yahoo
and Tinkoff, as `/news`, `/screen` and `/info` do. `ticker_status` and `bot_backtest` read stored
data only.

The tools' names, titles and descriptions are what a client lists in `tools/list`. The server
lists them sorted by name, whatever order `McpToolServer` was given; the order of the list is kept
for `McpToolServer.tools`. Every tool has one argument, which its input schema describes and marks
required: a call that omits it, or gives it the wrong type, gets an `Invalid arguments` error
result.

### `ticker_status`

A short text report from the stores and the router, for finding out why a ticker has no data or
looks stale. The ticker is upper-cased and trimmed, like the commands do.

```
SBER
Source setting: tinkoff, board TQBR (set for this ticker)
Adapter: none wired for tinkoff, so the sync skips this ticker
Watchlist: enabled, note "core holding"
Stored bars, latest date: yahoo 2026-03-02, tinkoff none
Metadata: read from yahoo, currency RUB, updated 3 days ago
Metadata origin: differs from the source setting; once an adapter is wired, the sync re-reads the metadata and refetches the bars
```

- *Source setting* is the setting stored for the ticker, or the default the router falls back to.
- *Adapter* says whether `MarketDataSources.forTicker` finds an adapter, and for which source and
  board. A source with no adapter is reported, not raised.
- *Watchlist* shows the entry of the account in `McpPorts.accountId` only.
- *Stored bars* lists the latest bar date per source a ticker can be set to.
- *Metadata origin* tells whether the stored metadata was read from the source and board the sync
  would read it from now. The sync re-reads metadata and refetches the bars when it differs. With
  no adapter wired the comparison uses the setting, and a Tinkoff setting without a board compares
  the source alone.

### `live_quote`

The latest quote of the ticker's adapter, bypassing stored data: `AAPL: 189.3 USD as of
2026-10-19T10:15:00Z (source yahoo)`. A source with no quote right now is an ordinary answer. A
`MarketDataError` comes back as an error result in plain words: an unknown ticker, a rate limit
with its retry delay, a source with no adapter wired, a temporary failure, any other failure.

### `bot_query`, `bot_backtest` and `bot_command`

Each takes a command line as the Telegram chat would, such as `/positions` or `/source AAPL
tinkoff`, and returns what the handlers replied:

- Text replies are joined into one text, separated by a blank line. A keyboard reply adds its
  button labels after its text.
- A document is shown as its file name and, when it is UTF-8 text such as a CSV, its content, cut
  at 20,000 characters with a note of how much is shown. Other documents are reported by name and
  size.
- A photo is returned as image content (`image/png`), as the equity curve of `/backtest` is.
- A command nobody handles answers `Unknown command. Try /help`, and a handler that fails answers
  `Command failed: ...`, exactly as in the chat.

The handlers send Telegram text, so replies keep its limits: legacy Markdown characters are
removed, underscores become spaces and a reply is cut at 4,000 characters.

A line that is not a command (it does not start with `/`) is refused with an error result.

#### Which lines `bot_query` runs

`bot_query` runs a line only when `CommandEffect.of(command, args)` says `ReadOnly`; it refuses
the others with an error result that names the tool to use. `bot_backtest` runs `/backtest` lines
only. `bot_command` runs any line.

| Effect | Lines |
|---|---|
| `Changing` | `/watchlist add SYM ...` and `/watchlist rm SYM`; `/source SYM yahoo`, `/source SYM tinkoff (BOARD)` and `/source SYM default`; `/alert price SYM ...` with a rule that parses and `/alert delete ID` with a numeric id; `/mute` and `/unmute` with any arguments |
| `Additive` | `/backtest` with a strategy and a ticker, which saves a run |
| `ReadOnly` | every other command, among them `/sweep` (computes only), `/export` and `/help`; the read forms `/watchlist`, `/source` and `/source SYM`, `/alert rules`; and any line the handler would answer with its usage text alone, such as `/watchlist add` without a symbol or `/backtest` with one argument |

The rules follow the grammar of each command's handler, and the match over `Command` has no
fallback, so a new command cannot be added without being classified.

### Conventions of every tool

[`McpToolBuilder`](#tools-of-your-own) gives every tool the same conventions, the bot tools' and
a consumer's own:

- The call is logged at info, in the bot's effect `F`, with the tool's name and its arguments as
  compact JSON, for instance `MCP tool call ticker_status with arguments {"ticker":"AAPL"}`. The
  entry carries the trace context of whatever `run` establishes for the call.
- Arguments that do not decode into the tool's input are answered with an error result that starts
  with `Invalid arguments` and names the field, and the work does not run.
- A problem the caller can read and correct, such as a line `bot_query` refuses, is raised as a
  `ToolFunction.ToolError` and answered with an error result (`isError`) carrying its text. It is
  not logged as an error.
- A tool whose work fails unexpectedly, such as a store that is down, answers with an error result
  that carries the failure's message, for instance `ticker_status failed: db down`, and logs the
  failure once at error level. Failures outside the tools, which the server answers with an
  internal error, are logged by `McpHttpServer` at error level.

## Building blocks

### Capturing replies

`CapturingTgGateway[F]` is a `TgGateway` that sends nothing: it keeps what the handlers send, per
chat, as `CapturedReply` values (`Text`, `TextWithKeyboard` with the button labels, `Document`
with its file name, bytes and caption, `Photo`) until `take(chatId)` removes and returns them.
`getUpdates` is an empty success and `answerCallbackQuery` does nothing.

`CommandRunner[F]` runs a command line through `CommandHandleService` under a synthetic chat of
its own and returns that chat's replies. Every run gets a new chat id from a counter, so runs in
flight at the same time never see each other's replies, and the replies of a run that is
cancelled are dropped. `CommandRunner.parse` reads a line without running it, which is how the
tools classify it first. A line that does not start with `/` comes back as
`CommandRunError.NotACommand`.

The consumer builds the gateway first, its handlers over it, and then the runner:

```scala
for {
  gateway <- CapturingTgGateway.create[I, F]
  handlers = CommandHandlers.standard(gateway, tcsFacade, extraHandlers(gateway))
  runner <- CommandRunnerImpl.create[I, F](gateway, handlers)
} yield runner
```

`CommandHandlers.standard(tgGateway, tcsFacade, extraHandlers)` is also what
`TgUpdatesProcessImpl.resource` uses: `/ping`, `/info`, the extra handlers, then `/help` listing
their usages. Building the extra handlers a second time over the capturing gateway gives the MCP
server its own handlers without touching the chat's.

### Ports, tools and the tool server

`McpPorts[F]` bundles what the tools read: `MarketDataSources`, `TickerSourceStorage`,
`WatchlistStorage`, `PriceHistoryStorage`, `InstrumentStorage`, the `CommandRunner` and the
`AccountId` whose watchlist is shown.

`McpToolBuilder.create[I, F, G](run)` creates, in `I`, the builder that gives the tools of a server
the conventions above. Their work is in `F`, and `run: F ~> G` runs it in `G`. A consumer creates
one builder and shares it between the bot tools and its own. [The effect bridge](#the-effect-bridge)
names the three effects.

`McpTools.create[F, G](ports, builder)` returns an `McpTools[F, G]` with the three groups as
`List[ToolFunction[G]]` members. Serving `changing` is only ever the consumer's explicit choice,
since no other member includes it.

`new McpToolServer[G](info, instructionText, toolFunctions)` is an `McpServer[G]` that serves a
fixed list of tools to every caller. Concatenate the groups in a fixed order. Take the tool names
in `instructionText` from `ToolNames`, so that renaming a tool cannot leave the text naming the old
one.

### HTTP

`McpHttpServer.resource(server, config)` allocates what the HTTP side needs (the sessions that
clients of older revisions open, held in memory for 30 minutes of idleness, and the origin check)
and returns an `McpHttpServer`:

- `routes: HttpRoutes[IO]` is the MCP route at `/mcp` behind the bearer-token check, for a
  consumer that serves it on a server of its own, and for tests through an in-memory client.
- `serve: Resource[IO, Server]` starts ember on `config.host` and `config.port`. When the resource
  is released the server waits at most 5 seconds for open connections, such as a client's event
  stream or a long tool call, before it closes them, so that stopping the process stays within a
  container's stop grace period.

`McpHttpConfig(host, port, token, allowedOrigins)` holds the settings. The token is an
`AccessToken`, an opaque type with no way to read the value back, no `Show` and no `Loggable`; it
prints as `AccessToken(<redacted>)`, and so does a configuration that holds it.

### The effect bridge

The library names three effects, each by one letter:

- `I` is the consumer's base effect, as in `Logs[I, F]`.
- `F` is the bot's effect, where the tools' work runs. `I` runs it once given a context.
- `G` is the effect the MCP server and its tools run in. The HTTP transport runs on `IO` only, so
  `G` is `IO` wherever `McpHttpServer` serves them.

A consumer whose `I` is not `IO` needs two conversions:

- Lifting the HTTP server into `I` is `cats.effect.LiftIO`:
  `McpHttpServer.resource(server, config).flatMap(_.serve).mapK(LiftIO.liftK[I])`. cats-effect has
  `LiftIO` instances for `Kleisli` and the other transformers, and zio-interop-cats has one for
  ZIO.
- Running the consumer's code inside the tools is `EffectBridge.toIO(dispatcher)`, an `I ~> IO`
  through a cats-effect `Dispatcher[I]`. It uses a cancelable future, so a client that drops its
  request cancels the work its tool call started. There is no type class for this direction in
  cats-effect 3.

A sketch for a consumer whose services run in `F`, with `runInI` running a program of `F` in `I`
under a trace context of its own (for `F[A] = ReaderT[I, ProcessContext, A]`, a fresh
`ProcessContext` with a trace id from `TraceIds.next`, and `fa.run`), and `served` choosing the
tools to serve from the bot tools and the builder:

```scala
def mcpServer[I[_]: Async: LiftIO, F[_]: MonadThrow: Clock](
  ports: McpPorts[F],
  runInI: F ~> I,
  config: McpHttpConfig,
  served: (McpTools[F, IO], McpToolBuilder[F, IO]) => List[ToolFunction[IO]]
)(implicit logsF: Logs[I, F], logsIO: Logs[IO, IO]): Resource[I, Server] =
  for {
    dispatcher <- Dispatcher.parallel[I]
    run = new (F ~> IO) {
      def apply[A](fa: F[A]): IO[A] = EffectBridge.toIO(dispatcher)(runInI(fa))
    }
    builder <- Resource.eval(McpToolBuilder.create[I, F, IO](run))
    tools = served(McpTools.create[F, IO](ports, builder), builder)
    server = new McpToolServer[IO](Implementation("example-bot", "1.0"), None, tools)
    running <- McpHttpServer.resource(server, config).flatMap(_.serve).mapK(LiftIO.liftK[I])
  } yield running
```

`(tools, _) => tools.readOnly ++ tools.additive` serves everything but `bot_command`; add
`tools.changing` where the operator allows writes, and the consumer's own tools built with the
`builder`.

## Tools of your own

A tool of the consumer's own is built by the same `McpToolBuilder`, so it follows the
[conventions](#conventions-of-every-tool) without copying them. `builder.text` takes work that
returns the reply as text, and `builder.content` work that returns `Content`, images included. The
input is a case class with a schema and a decoder; `NoArguments` is the input of a tool that takes
none, such as a status report or a database check:

```scala
val StatusInfo = ToolFunction.Info(
  name = "bot_status",
  title = Some("Bot status"),
  description = Some(
    "Reports how long the bot has been running and whether the database answers."
  ),
  effect = ToolFunction.Effect.ReadOnly,
  isOpenWorld = false
)

def statusTool[F[_], G[_]](builder: McpToolBuilder[F, G], report: F[String]): ToolFunction[G] =
  builder.text[NoArguments](StatusInfo)(_ => report)
```

A tool with arguments takes a case class instead. Derive its schema with scala-json-schema and
describe each field with `@JsonSchemaField("description", ...)`, which is what the model reads; the
schema marks every field required, so a field is never an `Option`. The arguments are logged in
full, so a tool should not take a secret as an argument.

## Security

- **Token.** Every request, to any path, must carry `Authorization: Bearer <token>`; anything else
  is answered with 401, `WWW-Authenticate: Bearer` and `Connection: close` (the request body is
  left unread, so the connection cannot be reused). The token is compared in constant time with
  `MessageDigest.isEqual`, and a blank token admits nobody. Generate a long random token and keep
  it in the consumer's secret store.
- **TLS.** The server speaks plain HTTP. Put it behind a reverse proxy that terminates TLS, or bind
  it to a loopback address; a bearer token sent in clear text can be read from the network.
- **Origins.** A request that carries an `Origin` header comes from a browser page and is served
  only if the origin is in `allowedOrigins`, written `scheme://host[:port]`; any other is answered
  with 403. The check does not look at the request's host, because a DNS rebinding attack has the
  same host as the request. A request without `Origin` comes from a program and is not checked, so
  an empty set refuses every browser and no agent. A malformed entry fails
  `McpHttpServer.resource`. The server adds no CORS headers: a page that calls it from a browser
  needs them from the reverse proxy, which also has to answer the preflight request, because that
  carries no token.
- **Writes are opt-in.** `bot_command` can change the watchlist, ticker sources, alert rules and
  alert muting, and `bot_backtest` adds backtest runs. The library never serves either by itself:
  the consumer picks the groups, and `bot_query` refuses what it should not run and points to the
  other tools, noting that the operator may not have enabled them.
- **One identity.** The bearer check runs before the MCP routes and does not pass the token on, so
  the tools see an anonymous caller and every holder of the token has the same rights.
- **Secrets in replies.** The handlers' replies and a failure's message are returned to the agent
  as they are; keep credentials out of exception messages.
