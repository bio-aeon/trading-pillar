package st.btu.trading.pillar.services

import org.specs2.mutable.Specification

import java.time.LocalDate
import scala.util.Try

class IbkrSyncConfigSpec extends Specification {

  "IbkrSyncConfig" >> {
    "construction" >> {
      "rejects a window of no days" >> {
        Try(IbkrSyncConfig(windowDays = 0)).isFailure must beTrue
      }

      "rejects a negative number of days to read again" >> {
        Try(IbkrSyncConfig(rereadDays = -1)).isFailure must beTrue
      }

      "accepts no days to read again" >> {
        Try(IbkrSyncConfig(rereadDays = 0)).isSuccess must beTrue
      }
    }

    "earliestHistory" >> {
      "is 1 January four calendar years before the year of the day, whatever the day of the year" >> {
        List(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 12, 31))
          .map(IbkrSyncConfig.earliestHistory) must_== List.fill(3)(LocalDate.of(2022, 1, 1))
      }

      "moves to the next 1 January with the new year" >> {
        IbkrSyncConfig.earliestHistory(LocalDate.of(2027, 1, 1)) must_== LocalDate.of(2023, 1, 1)
      }
    }
  }
}
