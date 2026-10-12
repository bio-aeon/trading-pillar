package st.btu.trading.pillar.charts

import st.btu.trading.pillar.strategy.backtest.EquityPoint

import java.awt.image.BufferedImage
import java.awt.{BasicStroke, Color, RenderingHints}
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import scala.math.BigDecimal.RoundingMode

/** Minimal, dependency-free PNG line chart of a backtest's equity curve, rendered with
  * `java.awt`/`javax.imageio`. A pure function over its input; the caller is responsible for
  * catching any rendering failure.
  */
object EquityCurveImage {

  // java.awt needs this set before any Graphics2D is created when there is no display (a
  // Telegram bot process has none); setting it here, once, means no -Djava.awt.headless JVM flag
  // is required at the call site.
  System.setProperty("java.awt.headless", "true")

  private val Margin = 50
  private val Background = Color.WHITE
  private val AxisColor = new Color(60, 60, 60)
  private val LineColor = new Color(30, 110, 200)
  private val DateFormat = DateTimeFormatter.ISO_LOCAL_DATE

  /** Renders `points` as a `width` by `height` PNG line chart, with axis labels for the min/max
    * equity and the first/last date.
    *
    * @return
    *   the encoded PNG bytes
    */
  def render(points: List[EquityPoint], width: Int = 900, height: Int = 400): Array[Byte] = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setColor(Background)
      g.fillRect(0, 0, width, height)
      g.setColor(AxisColor)
      g.drawRect(Margin, Margin, width - 2 * Margin, height - 2 * Margin)

      if (points.nonEmpty) drawSeries(g, points, width, height)

      val out = new ByteArrayOutputStream()
      ImageIO.write(image, "png", out)
      out.toByteArray
    } finally g.dispose()
  }

  private def drawSeries(
    g: java.awt.Graphics2D,
    points: List[EquityPoint],
    width: Int,
    height: Int
  ): Unit = {
    val equities = points.map(_.equity)
    val maxEquity = equities.max
    val minEquity = equities.min
    val range = (maxEquity - minEquity).toDouble

    def x(i: Int): Int =
      if (points.size <= 1) Margin
      else Margin + i * (width - 2 * Margin) / (points.size - 1)

    def y(equity: BigDecimal): Int =
      if (range == 0d) height / 2
      else {
        val fraction = (equity - minEquity).toDouble / range
        height - Margin - math.round(fraction * (height - 2 * Margin)).toInt
      }

    if (points.size >= 2) {
      g.setColor(LineColor)
      g.setStroke(new BasicStroke(2f))
      points.zipWithIndex.sliding(2).foreach {
        case Seq((p1, i1), (p2, i2)) => g.drawLine(x(i1), y(p1.equity), x(i2), y(p2.equity))
        case _ => ()
      }
    }

    g.setColor(AxisColor)
    g.drawString(formatEquity(maxEquity), 4, Margin + 10)
    g.drawString(formatEquity(minEquity), 4, height - Margin)

    val fm = g.getFontMetrics
    val firstLabel = points.head.date.format(DateFormat)
    val lastLabel = points.last.date.format(DateFormat)
    g.drawString(firstLabel, Margin, height - Margin + 20)
    g.drawString(lastLabel, width - Margin - fm.stringWidth(lastLabel), height - Margin + 20)
  }

  private def formatEquity(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toString
}
