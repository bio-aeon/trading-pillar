package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.tg.MarkdownSanitizer

// TgGateway sends replies with parse_mode=Markdown (Telegram's legacy v1 flavour) - see
// MarkdownSanitizer for why every reply is routed through it first. Every reply in this package is
// then routed through `prepare` before being handed to TgGateway.sendMessage, which additionally
// caps it at 4000 characters, comfortably under Telegram's 4096-character message limit.
object CommandReply {
  private[pillar] val MaxLength = 4000

  def prepare(text: String): String = {
    val safe = MarkdownSanitizer.sanitize(text)
    if (safe.length > MaxLength) safe.take(MaxLength) else safe
  }
}
