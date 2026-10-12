package st.btu.trading.pillar.models.tg

// Legacy Telegram Markdown (parse_mode=Markdown) sanitizer, shared by every outgoing message this
// library builds (chat command replies, alert notifications): an unescaped `_ * ` [ ]` can
// silently reformat the message, or make TgGateway.sendMessage fail outright with "can't parse
// entities". Underscores show up in our own identifiers (e.g. AlertKind values like strategy_buy),
// so they are turned into spaces rather than dropped; the remaining markup characters are just
// removed.
private[pillar] object MarkdownSanitizer {

  private val Markup = """[*`\[\]]""".r

  def sanitize(text: String): String = Markup.replaceAllIn(text.replace('_', ' '), "")
}
