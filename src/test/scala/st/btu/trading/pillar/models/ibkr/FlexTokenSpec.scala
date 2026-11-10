package st.btu.trading.pillar.models.ibkr

import cats.syntax.show.*
import org.specs2.mutable.Specification
import tofu.logging.Loggable

class FlexTokenSpec extends Specification {

  private val token = FlexToken("s3cr3t-t0ken")

  "FlexToken" >> {
    "show" >> {
      "masks the token" >> {
        token.show must_== "FlexToken(***)"
      }
    }

    "loggable" >> {
      "masks the token" >> {
        Loggable[FlexToken].logShow(token) must_== "FlexToken(***)"
      }
    }
  }
}
