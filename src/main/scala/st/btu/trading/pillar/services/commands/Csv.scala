package st.btu.trading.pillar.services.commands

// Minimal RFC 4180-style CSV writer for ExportHandler's three report shapes: quotes a field only
// when it contains a comma, a double quote or a line break (doubling any embedded quote), leaves
// every other field bare, and joins rows with CRLF (the RFC's own line terminator, and what
// spreadsheet tools expect). Not a general-purpose CSV library - no header validation, no
// per-column typing.
object Csv {

  def write(header: List[String], rows: List[List[String]]): String =
    (header :: rows).iterator.map(row => row.map(quote).mkString(",")).mkString("", "\r\n", "\r\n")

  private def quote(field: String): String =
    if (field.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r'))
      "\"" + field.replace("\"", "\"\"") + "\""
    else field
}
