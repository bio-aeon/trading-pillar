package st.btu.trading.pillar.models.binance

import cats.syntax.show.*
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import tofu.logging.Loggable

class BinanceKeysSpec extends Specification {

  private val apiKey = ApiKey("vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A")
  private val secretKey = SecretKey(
    "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"
  )

  "ApiKey" >> {
    "show" >> {
      "masks the key" >> {
        apiKey.show must_== "ApiKey(***)"
      }
    }

    "loggable" >> {
      "masks the key" >> {
        Loggable[ApiKey].logShow(apiKey) must_== "ApiKey(***)"
      }
    }
  }

  "SecretKey" >> {
    "show" >> {
      "masks the key" >> {
        secretKey.show must_== "SecretKey(***)"
      }
    }

    "loggable" >> {
      "masks the key" >> {
        Loggable[SecretKey].logShow(secretKey) must_== "SecretKey(***)"
      }
    }
  }

  "BinanceAccount" >> {
    "toString" >> {
      "names the account and neither key" >> {
        val printed = BinanceAccount(AccountId(7), apiKey, secretKey).toString
        printed must contain("7")
        printed must not(contain("vmPUZE6"))
        printed must not(contain("NhqPtmd"))
      }
    }
  }
}
