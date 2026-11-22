package st.btu.trading.pillar.gateways.binance

import sttp.model.{Method, Uri}

// What a bucket of request weight is counted against.
private[binance] enum Scope {

  /** The IP address of the caller, whichever account calls. */
  case Ip

  /** The account the key belongs to. */
  case Account
}

// A limit of request weight per minute that Binance counts calls against. All the calls of
// /api/v3 share one bucket, the market data host has one of its own, and every /sapi endpoint has
// a bucket of its own, "independent" of the others.
private[binance] enum Bucket(val limit: Int, val scope: Scope) {
  case Api extends Bucket(RestIpLimit, Scope.Ip)
  case Market extends Bucket(RestIpLimit, Scope.Ip)
  case KeyRestrictions extends Bucket(SapiIpLimit, Scope.Ip)
  case DelistSchedule extends Bucket(SapiIpLimit, Scope.Ip)
  case Coins extends Bucket(SapiIpLimit, Scope.Ip)
  case Deposits extends Bucket(SapiIpLimit, Scope.Ip)
  case FiatPayments extends Bucket(SapiIpLimit, Scope.Ip)
  case FundingAsset extends Bucket(SapiIpLimit, Scope.Ip)
  case AssetDividend extends Bucket(SapiIpLimit, Scope.Ip)
  case Dribblet extends Bucket(SapiIpLimit, Scope.Ip)
  case Withdrawals extends Bucket(SapiAccountLimit, Scope.Account)
  case FiatOrders extends Bucket(SapiAccountLimit, Scope.Account)
  case ConvertTradeFlow extends Bucket(SapiAccountLimit, Scope.Account)
}

// An endpoint the clients call, with the request weight Binance documents for it and its HTTP
// method. Signed calls and `exchangeInfo` go to the API host, `klines` and the ticker price to the
// market data host, which needs no key.
private[binance] sealed trait Endpoint {
  def path: String
  def weight: Int
  def bucket: Bucket
  def method: Method

  // The address of the endpoint, with no query.
  def uri: Uri
}

// The endpoints that read public data, and so carry neither the key nor a signature.
private[binance] enum PublicEndpoint(
  host: String,
  val path: String,
  val weight: Int,
  val bucket: Bucket
) extends Endpoint {
  case Time extends PublicEndpoint(ApiHost, "/api/v3/time", 1, Bucket.Api)
  case ExchangeInfo extends PublicEndpoint(ApiHost, "/api/v3/exchangeInfo", 20, Bucket.Api)
  case Klines extends PublicEndpoint(MarketHost, "/api/v3/klines", 2, Bucket.Market)
  case TickerPrice extends PublicEndpoint(MarketHost, "/api/v3/ticker/price", 2, Bucket.Market)

  val method: Method = Method.GET
  val uri: Uri = Uri.unsafeParse(host + path)
}

// The endpoints that read the account, and so carry the key and a signature. They are read with
// GET unless a case says otherwise.
private[binance] enum SignedEndpoint(
  host: String,
  val path: String,
  val weight: Int,
  val bucket: Bucket,
  val method: Method = Method.GET
) extends Endpoint {
  case MyTrades extends SignedEndpoint(ApiHost, "/api/v3/myTrades", 20, Bucket.Api)
  case SpotAccount extends SignedEndpoint(ApiHost, "/api/v3/account", 20, Bucket.Api)
  case KeyRestrictions
      extends SignedEndpoint(ApiHost, "/sapi/v1/account/apiRestrictions", 1, Bucket.KeyRestrictions)
  case DelistSchedule
      extends SignedEndpoint(ApiHost, "/sapi/v1/spot/delist-schedule", 100, Bucket.DelistSchedule)
  case Coins extends SignedEndpoint(ApiHost, "/sapi/v1/capital/config/getall", 10, Bucket.Coins)
  case Deposits
      extends SignedEndpoint(ApiHost, "/sapi/v1/capital/deposit/hisrec", 1, Bucket.Deposits)
  case Withdrawals
      extends SignedEndpoint(
        ApiHost,
        "/sapi/v1/capital/withdraw/history",
        18000,
        Bucket.Withdrawals
      )
  case FiatOrders extends SignedEndpoint(ApiHost, "/sapi/v1/fiat/orders", 45000, Bucket.FiatOrders)
  case FiatPayments
      extends SignedEndpoint(ApiHost, "/sapi/v1/fiat/payments", 1, Bucket.FiatPayments)
  case ConvertTradeFlow
      extends SignedEndpoint(ApiHost, "/sapi/v1/convert/tradeFlow", 3000, Bucket.ConvertTradeFlow)
  case FundingAsset
      extends SignedEndpoint(
        ApiHost,
        "/sapi/v1/asset/get-funding-asset",
        1,
        Bucket.FundingAsset,
        Method.POST
      )
  case AssetDividend
      extends SignedEndpoint(ApiHost, "/sapi/v1/asset/assetDividend", 10, Bucket.AssetDividend)
  case Dribblet extends SignedEndpoint(ApiHost, "/sapi/v1/asset/dribblet", 1, Bucket.Dribblet)

  val uri: Uri = Uri.unsafeParse(host + path)
}

private val ApiHost = "https://api.binance.com"
private val MarketHost = "https://data-api.binance.vision"

// Binance's limits of request weight per minute: of /api/v3 and of the market data host per IP
// address, and of each /sapi endpoint per IP address or per account.
private val RestIpLimit = 6000
private val SapiIpLimit = 12000
private val SapiAccountLimit = 180000
