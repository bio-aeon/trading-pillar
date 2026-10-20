package st.btu.trading.pillar.mcp

import com.comcast.ip4s.*
import org.specs2.mutable.Specification

class AccessTokenSpec extends Specification {

  "AccessToken" >> {
    "admits" >> {

      "the token itself" >> {
        AccessToken("s3cret-token").admits("s3cret-token") must beTrue
      }

      "a value that differs from the token in one character" >> {
        AccessToken("s3cret-token").admits("s3cret-tokex") must beFalse
      }

      "no prefix of the token" >> {
        AccessToken("s3cret-token").admits("s3cret") must beFalse
      }

      "no extension of the token" >> {
        AccessToken("s3cret-token").admits("s3cret-token!") must beFalse
      }

      "nobody when the token is blank" >> {
        (AccessToken("").admits("") must beFalse) and (AccessToken("  ").admits("  ") must beFalse)
      }
    }

    "rendering" >> {

      "hides the secret when the token is printed" >> {
        AccessToken("s3cret-token").toString must not(contain("s3cret"))
      }

      "hides the secret when the configuration holding it is printed" >> {
        val config =
          McpHttpConfig(host"127.0.0.1", port"8080", AccessToken("s3cret-token"), Set.empty)
        config.toString must not(contain("s3cret"))
      }
    }
  }
}
