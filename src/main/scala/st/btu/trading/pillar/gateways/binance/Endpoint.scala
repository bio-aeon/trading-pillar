package st.btu.trading.pillar.gateways.binance

import sttp.model.Uri

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
  case Withdrawals extends Bucket(SapiAccountLimit, Scope.Account)
  case FiatOrders extends Bucket(SapiAccountLimit, Scope.Account)
  case ConvertTradeFlow extends Bucket(SapiAccountLimit, Scope.Account)
}

// Whether a call reads the account, and so carries the key and a signature, or reads public data.
private[binance] enum Access {
  case Public
  case Signed
}

// The endpoints the client calls, with the request weight Binance documents for each and their
// access. Signed calls and `exchangeInfo` go to the API host, `klines` to the market data host,
// which needs no key.
private[binance] enum Endpoint(
  host: String,
  val path: String,
  val weight: Int,
  val bucket: Bucket,
  val access: Access
) {
  case Time extends Endpoint(ApiHost, "/api/v3/time", 1, Bucket.Api, Access.Public)
  case ExchangeInfo extends Endpoint(ApiHost, "/api/v3/exchangeInfo", 20, Bucket.Api, Access.Public)
  case MyTrades extends Endpoint(ApiHost, "/api/v3/myTrades", 20, Bucket.Api, Access.Signed)
  case Klines extends Endpoint(MarketHost, "/api/v3/klines", 2, Bucket.Market, Access.Public)
  case KeyRestrictions
      extends Endpoint(
        ApiHost,
        "/sapi/v1/account/apiRestrictions",
        1,
        Bucket.KeyRestrictions,
        Access.Signed
      )
  case DelistSchedule
      extends Endpoint(
        ApiHost,
        "/sapi/v1/spot/delist-schedule",
        100,
        Bucket.DelistSchedule,
        Access.Signed
      )
  case Coins
      extends Endpoint(ApiHost, "/sapi/v1/capital/config/getall", 10, Bucket.Coins, Access.Signed)
  case Deposits
      extends Endpoint(
        ApiHost,
        "/sapi/v1/capital/deposit/hisrec",
        1,
        Bucket.Deposits,
        Access.Signed
      )
  case Withdrawals
      extends Endpoint(
        ApiHost,
        "/sapi/v1/capital/withdraw/history",
        18000,
        Bucket.Withdrawals,
        Access.Signed
      )
  case FiatOrders
      extends Endpoint(ApiHost, "/sapi/v1/fiat/orders", 45000, Bucket.FiatOrders, Access.Signed)
  case FiatPayments
      extends Endpoint(ApiHost, "/sapi/v1/fiat/payments", 1, Bucket.FiatPayments, Access.Signed)
  case ConvertTradeFlow
      extends Endpoint(
        ApiHost,
        "/sapi/v1/convert/tradeFlow",
        3000,
        Bucket.ConvertTradeFlow,
        Access.Signed
      )

  // The address of the endpoint, with no query.
  val uri: Uri = Uri.unsafeParse(host + path)
}

private val ApiHost = "https://api.binance.com"
private val MarketHost = "https://data-api.binance.vision"

// Binance's limits of request weight per minute: of /api/v3 and of the market data host per IP
// address, and of each /sapi endpoint per IP address or per account.
private val RestIpLimit = 6000
private val SapiIpLimit = 12000
private val SapiAccountLimit = 180000
