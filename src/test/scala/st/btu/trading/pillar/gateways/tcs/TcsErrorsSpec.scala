package st.btu.trading.pillar.gateways.tcs

import io.grpc.Status
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}

class TcsErrorsSpec extends Specification {

  private val ticker = Ticker("SBER")

  "TcsErrors" >> {
    "toMarketDataError" >> {

      "maps an unknown instrument to not-found when a ticker is given" >> {
        val mapper = TcsErrors.toMarketDataError(Some(ticker))
        mapper(Status.NOT_FOUND.asRuntimeException()) must_== MarketDataError.NotFound(ticker)
      }

      "reports an unknown instrument as a generic failure when no ticker is given" >> {
        val mapper = TcsErrors.toMarketDataError(None)
        val unknown = Status.NOT_FOUND.withDescription("no such figi").asRuntimeException()
        mapper(unknown) must_== MarketDataError.Other("no such figi", Some(unknown))
      }

      "maps an exhausted quota to rate limiting regardless of ticker" >> {
        val exhausted = Status.RESOURCE_EXHAUSTED.asRuntimeException()
        TcsErrors.toMarketDataError(Some(ticker))(exhausted) must_== MarketDataError.RateLimited(
          None
        )
        TcsErrors.toMarketDataError(None)(exhausted) must_== MarketDataError.RateLimited(None)
      }

      List(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED).foreach { code =>
        s"maps $code to a transient failure keeping the status text" >> {
          val failure = code.toStatus.withDescription("down").asRuntimeException()
          TcsErrors.toMarketDataError(Some(ticker))(failure) must_==
            MarketDataError.Transient("down", Some(failure))
        }
      }

      "names the status code in a transient failure when the status has no description" >> {
        val late = Status.DEADLINE_EXCEEDED.asRuntimeException()
        TcsErrors.toMarketDataError(Some(ticker))(late) must_==
          MarketDataError.Transient("DEADLINE_EXCEEDED", Some(late))
      }

      "maps any other status to a generic failure keeping the cause" >> {
        val denied = Status.PERMISSION_DENIED.withDescription("no token").asRuntimeException()
        TcsErrors.toMarketDataError(Some(ticker))(denied) must_==
          MarketDataError.Other("no token", Some(denied))
      }

      "maps a failure that is not a gRPC status to a generic failure using its message" >> {
        val boom = new RuntimeException("unexpected")
        TcsErrors.toMarketDataError(Some(ticker))(boom) must_==
          MarketDataError.Other("unexpected", Some(boom))
      }
    }

    "isNotFound" >> {

      "recognises only the not-found status" >> {
        TcsErrors.isNotFound(Status.NOT_FOUND.asRuntimeException()) must beTrue
        TcsErrors.isNotFound(Status.UNAVAILABLE.asRuntimeException()) must beFalse
        TcsErrors.isNotFound(new RuntimeException("NOT_FOUND")) must beFalse
      }
    }
  }
}
