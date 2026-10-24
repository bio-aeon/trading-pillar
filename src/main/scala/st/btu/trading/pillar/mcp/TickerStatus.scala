package st.btu.trading.pillar.mcp

import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.services.commands.SourceSettings

import java.time.{Duration, Instant, LocalDate}

private[mcp] final case class LastBar(source: Source, date: Option[LocalDate])

// What the bot knows about one ticker, gathered for the report.
private[mcp] final case class TickerStatus(
  ticker: Ticker,
  storedSetting: Option[TickerSource],
  setting: SourceSetting,
  adapter: Option[SourceSetting],
  watchlistEntry: Option[WatchlistItem],
  lastBars: List[LastBar],
  metadata: Option[InstrumentInfo],
  now: Instant
)

private[mcp] object TickerStatus {

  // The sources a ticker can be set to, hence the ones bars can be stored from.
  val BarSources: List[Source] = Source.values.toList.filter(servesBars)

  private def servesBars(source: Source): Boolean =
    source match {
      case Source.Yahoo | Source.Tinkoff => true
      case Source.Binance => false
    }

  def render(status: TickerStatus): String =
    List(
      status.ticker.value,
      settingLine(status),
      adapterLine(status),
      watchlistLine(status.watchlistEntry),
      barsLine(status.lastBars)
    ).appendedAll(metadataLines(status)).mkString("\n")

  private def settingLine(status: TickerStatus): String = {
    val basis =
      if (status.storedSetting.isDefined) "set for this ticker"
      else "none set for this ticker, so the default"
    s"Source setting: ${SourceSettings.describe(status.setting)} ($basis)"
  }

  private def adapterLine(status: TickerStatus): String =
    status.adapter match {
      case Some(origin) => s"Adapter: wired (${SourceSettings.describe(origin)})"
      case None =>
        s"Adapter: none wired for ${status.setting.source.value}, so the sync skips this ticker"
    }

  private def watchlistLine(entry: Option[WatchlistItem]): String =
    entry match {
      case Some(item) =>
        val state = if (item.enabled) "enabled" else "disabled"
        val note = item.note.filter(_.nonEmpty).fold("")(text => s", note \"$text\"")
        s"Watchlist: $state$note"
      case None => "Watchlist: not on the watchlist"
    }

  private def barsLine(lastBars: List[LastBar]): String = {
    val perSource = lastBars.map(bar => s"${bar.source.value} ${bar.date.fold("none")(_.toString)}")
    s"Stored bars, latest date: ${perSource.mkString(", ")}"
  }

  private def metadataLines(status: TickerStatus): List[String] =
    status.metadata match {
      case None => List("Metadata: none stored")
      case Some(info) =>
        val age = describeAge(Duration.between(info.updatedAt.toInstant, status.now))
        val origin = SourceSettings.describe(info.origin)
        List(
          s"Metadata: read from $origin, currency ${info.currency.value}, updated $age",
          originLine(status, info.origin)
        )
    }

  private def originLine(status: TickerStatus, metadataOrigin: SourceSetting): String =
    if (originMatches(status, metadataOrigin)) "Metadata origin: matches the source setting"
    else if (status.adapter.isDefined)
      "Metadata origin: differs from the source setting, " +
        "so the next sync re-reads the metadata and refetches the bars"
    else
      "Metadata origin: differs from the source setting; " +
        "once an adapter is wired, the sync re-reads the metadata and refetches the bars"

  // The sync compares the metadata with the adapter that serves the ticker. Without an adapter the
  // setting is all there is to compare with, and a Tinkoff setting that names no board leaves the
  // board open.
  private def originMatches(status: TickerStatus, metadataOrigin: SourceSetting): Boolean = {
    val expected = status.adapter.orElse(settingOrigin(status.setting))
    expected match {
      case Some(origin) => origin == metadataOrigin
      case None => status.setting.source == metadataOrigin.source
    }
  }

  private def settingOrigin(setting: SourceSetting): Option[SourceSetting] =
    setting match {
      case SourceSetting.Yahoo(_) | SourceSetting.Tinkoff(Some(_)) => Some(setting)
      case SourceSetting.Tinkoff(None) => None
    }

  private def describeAge(age: Duration): String = {
    val nonNegative = if (age.isNegative) Duration.ZERO else age
    if (nonNegative.toDays > 0) plural(nonNegative.toDays, "day")
    else if (nonNegative.toHours > 0) plural(nonNegative.toHours, "hour")
    else if (nonNegative.toMinutes > 0) plural(nonNegative.toMinutes, "minute")
    else "less than a minute ago"
  }

  private def plural(count: Long, unit: String): String =
    s"$count $unit${if (count == 1) "" else "s"} ago"
}
