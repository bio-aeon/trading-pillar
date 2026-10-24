package st.btu.trading.pillar.mcp

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.market.*

import java.time.{Instant, LocalDate, ZoneOffset, ZonedDateTime}

class TickerStatusSpec extends Specification {

  private val ticker = Ticker("SBER")
  private val vod = Ticker("VOD")
  private val tqbr = ClassCode("TQBR")
  private val cets = ClassCode("CETS")
  private val vodL = YahooSymbol("VOD.L")
  private val vodMx = YahooSymbol("VOD.MX")
  private val now = Instant.parse("2026-10-19T10:00:00Z")

  private def metadataFrom(
    origin: SourceSetting,
    updatedAt: String = "2026-10-19T09:00:00Z",
    forTicker: Ticker = ticker
  ): InstrumentInfo =
    InstrumentInfo(
      forTicker,
      origin,
      None,
      "EQUITY",
      Currency("RUB"),
      "MISX",
      "Europe/Moscow",
      None,
      ZonedDateTime.ofInstant(Instant.parse(updatedAt), ZoneOffset.UTC)
    )

  private def status(
    setting: SourceSetting = SourceSetting.Yahoo(None),
    stored: Boolean = false,
    adapter: Option[SourceSetting] = None,
    entry: Option[WatchlistItem] = None,
    metadata: Option[InstrumentInfo] = None,
    lastBars: List[LastBar] = List(LastBar(Source.Yahoo, None), LastBar(Source.Tinkoff, None)),
    forTicker: Ticker = ticker
  ): TickerStatus =
    TickerStatus(
      forTicker,
      Option.when(stored)(TickerSource(forTicker, setting)),
      setting,
      adapter,
      entry,
      lastBars,
      metadata,
      now
    )

  private def watchlistItem(enabled: Boolean, note: Option[String]): WatchlistItem =
    WatchlistItem(AccountId(1), ticker, enabled, note, ZonedDateTime.now(ZoneOffset.UTC))

  // The line of the report that starts with `label`.
  private def line(report: String, label: String): String =
    report.linesIterator.find(_.startsWith(label)).getOrElse(s"no line starting with $label")

  private def render(status: TickerStatus): String = TickerStatus.render(status)

  "TickerStatus" >> {
    "render" >> {

      "starts with the ticker" >> {
        render(status()).linesIterator.next() must_== "SBER"
      }

      "source setting" >> {

        "tells a setting stored for the ticker from the default" >> {
          (line(render(status(SourceSetting.Yahoo(None), stored = true)), "Source setting") must_==
            "Source setting: yahoo (set for this ticker)") and
            (line(render(status(SourceSetting.Yahoo(None))), "Source setting") must_==
              "Source setting: yahoo (none set for this ticker, so the default)")
        }

        "names the board of a Tinkoff setting" >> {
          line(
            render(status(SourceSetting.Tinkoff(Some(tqbr)), stored = true)),
            "Source setting"
          ) must_== "Source setting: tinkoff, board TQBR (set for this ticker)"
        }

        "names the Yahoo symbol of a Yahoo setting" >> {
          line(
            render(status(SourceSetting.Yahoo(Some(vodL)), stored = true, forTicker = vod)),
            "Source setting"
          ) must_== "Source setting: yahoo, symbol VOD.L (set for this ticker)"
        }
      }

      "adapter" >> {

        "names the source and board of a wired adapter" >> {
          (line(
            render(status(adapter = Some(SourceSetting.Yahoo(None)))),
            "Adapter"
          ) must_== "Adapter: wired (yahoo)") and
            (line(
              render(status(adapter = Some(SourceSetting.Tinkoff(Some(tqbr))))),
              "Adapter"
            ) must_== "Adapter: wired (tinkoff, board TQBR)")
        }

        "names the Yahoo symbol of a wired adapter" >> {
          line(
            render(status(adapter = Some(SourceSetting.Yahoo(Some(vodL))), forTicker = vod)),
            "Adapter"
          ) must_== "Adapter: wired (yahoo, symbol VOD.L)"
        }

        "says the sync skips the ticker when no adapter is wired" >> {
          line(render(status(SourceSetting.Tinkoff(None))), "Adapter") must_==
            "Adapter: none wired for tinkoff, so the sync skips this ticker"
        }
      }

      "watchlist" >> {

        "shows an enabled entry with its note" >> {
          line(render(status(entry = Some(watchlistItem(true, Some("core"))))), "Watchlist") must_==
            "Watchlist: enabled, note \"core\""
        }

        "shows a disabled entry without a note" >> {
          line(render(status(entry = Some(watchlistItem(false, None)))), "Watchlist") must_==
            "Watchlist: disabled"
        }

        "leaves out an empty note" >> {
          line(render(status(entry = Some(watchlistItem(true, Some(""))))), "Watchlist") must_==
            "Watchlist: enabled"
        }

        "says when the ticker is not on the watchlist" >> {
          line(render(status()), "Watchlist") must_== "Watchlist: not on the watchlist"
        }
      }

      "stored bars" >> {

        "shows the latest date per source" >> {
          line(
            render(
              status(lastBars =
                List(
                  LastBar(Source.Yahoo, Some(LocalDate.of(2026, 3, 2))),
                  LastBar(Source.Tinkoff, None)
                )
              )
            ),
            "Stored bars"
          ) must_== "Stored bars, latest date: yahoo 2026-03-02, tinkoff none"
        }
      }

      "metadata" >> {

        "says when none is stored" >> {
          render(status()).linesIterator.toList.last must_== "Metadata: none stored"
        }

        "shows where it was read from, its currency and its age" >> {
          line(
            render(
              status(metadata =
                Some(metadataFrom(SourceSetting.Tinkoff(Some(cets)), "2026-10-19T04:30:00Z"))
              )
            ),
            "Metadata:"
          ) must_== "Metadata: read from tinkoff, board CETS, currency RUB, updated 5 hours ago"
        }

        "names the Yahoo symbol it was read under" >> {
          line(
            render(
              status(
                metadata = Some(metadataFrom(SourceSetting.Yahoo(Some(vodL)), forTicker = vod)),
                forTicker = vod
              )
            ),
            "Metadata:"
          ) must_== "Metadata: read from yahoo, symbol VOD.L, currency RUB, updated 1 hour ago"
        }

        "reports its age in the largest whole unit" >> {
          def age(updatedAt: String): String =
            line(
              render(status(metadata = Some(metadataFrom(SourceSetting.Yahoo(None), updatedAt)))),
              "Metadata:"
            )
              .split(", updated ")
              .last

          List(
            age("2026-10-18T10:00:00Z"),
            age("2026-10-12T10:00:00Z"),
            age("2026-10-19T09:00:00Z"),
            age("2026-10-19T09:57:00Z"),
            age("2026-10-19T09:59:30Z"),
            age("2026-10-19T10:05:00Z")
          ) must_== List(
            "1 day ago",
            "7 days ago",
            "1 hour ago",
            "3 minutes ago",
            "less than a minute ago",
            "less than a minute ago"
          )
        }
      }

      "metadata origin" >> {

        "matches when it was read from the adapter that serves the ticker" >> {
          line(
            render(
              status(
                SourceSetting.Tinkoff(None),
                adapter = Some(SourceSetting.Tinkoff(Some(tqbr))),
                metadata = Some(metadataFrom(SourceSetting.Tinkoff(Some(tqbr))))
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: matches the source setting"
        }

        "matches when it was read under the Yahoo symbol of the adapter that serves the ticker" >> {
          line(
            render(
              status(
                SourceSetting.Yahoo(Some(vodL)),
                adapter = Some(SourceSetting.Yahoo(Some(vodL))),
                metadata = Some(metadataFrom(SourceSetting.Yahoo(Some(vodL)), forTicker = vod)),
                forTicker = vod
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: matches the source setting"
        }

        "differs when it was read on another board than the adapter's" >> {
          line(
            render(
              status(
                SourceSetting.Tinkoff(Some(cets)),
                adapter = Some(SourceSetting.Tinkoff(Some(cets))),
                metadata = Some(metadataFrom(SourceSetting.Tinkoff(Some(tqbr))))
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: differs from the source setting, " +
            "so the next sync re-reads the metadata and refetches the bars"
        }

        "differs when it was read under another Yahoo symbol than the adapter's" >> {
          line(
            render(
              status(
                SourceSetting.Yahoo(Some(vodL)),
                adapter = Some(SourceSetting.Yahoo(Some(vodL))),
                metadata = Some(metadataFrom(SourceSetting.Yahoo(Some(vodMx)), forTicker = vod)),
                forTicker = vod
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: differs from the source setting, " +
            "so the next sync re-reads the metadata and refetches the bars"
        }

        "differs when it was read without the Yahoo symbol the setting names, with no adapter wired" >> {
          line(
            render(
              status(
                SourceSetting.Yahoo(Some(vodL)),
                metadata = Some(metadataFrom(SourceSetting.Yahoo(None), forTicker = vod)),
                forTicker = vod
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: differs from the source setting; " +
            "once an adapter is wired, the sync re-reads the metadata and refetches the bars"
        }

        "differs when it was read from another source, and the sync waits for an adapter" >> {
          line(
            render(
              status(
                SourceSetting.Tinkoff(Some(tqbr)),
                metadata = Some(metadataFrom(SourceSetting.Yahoo(None)))
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: differs from the source setting; " +
            "once an adapter is wired, the sync re-reads the metadata and refetches the bars"
        }

        "matches by source alone when no adapter is wired and the setting names no board" >> {
          line(
            render(
              status(
                SourceSetting.Tinkoff(None),
                metadata = Some(metadataFrom(SourceSetting.Tinkoff(Some(tqbr))))
              )
            ),
            "Metadata origin"
          ) must_== "Metadata origin: matches the source setting"
        }

        "matches a Yahoo setting with no symbol when no adapter is wired" >> {
          line(
            render(status(metadata = Some(metadataFrom(SourceSetting.Yahoo(None))))),
            "Metadata origin"
          ) must_== "Metadata origin: matches the source setting"
        }
      }
    }
  }
}
