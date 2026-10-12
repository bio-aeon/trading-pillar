package st.btu.trading.pillar.charts

import org.specs2.mutable.Specification
import st.btu.trading.pillar.strategy.backtest.EquityPoint

import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.imageio.ImageIO

class EquityCurveImageSpec extends Specification {

  private val PngHeader: List[Byte] =
    List(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  private def point(date: LocalDate, equity: BigDecimal): EquityPoint =
    EquityPoint(date, equity, equity, 0L)

  "EquityCurveImage" >> {
    "render" >> {
      "produces a valid, non-trivial PNG for a rising equity curve" >> {
        val points =
          (0 until 30).toList.map(i =>
            point(LocalDate.of(2024, 1, 1).plusDays(i.toLong), 10000 + i * 50)
          )

        val bytes = EquityCurveImage.render(points)

        bytes.take(8).toList must_== PngHeader
        bytes.length must be_>(500)
      }

      "does not crash on a flat equity curve (max == min)" >> {
        val points =
          List(point(LocalDate.of(2024, 1, 1), 10000), point(LocalDate.of(2024, 1, 2), 10000))

        val bytes = EquityCurveImage.render(points)

        bytes.take(8).toList must_== PngHeader
      }

      "does not crash on an empty or single-point series" >> {
        EquityCurveImage.render(Nil).take(8).toList must_== PngHeader
        EquityCurveImage
          .render(List(point(LocalDate.of(2024, 1, 1), 10000)))
          .take(8)
          .toList must_== PngHeader
      }

      "respects the given width and height" >> {
        val points =
          List(point(LocalDate.of(2024, 1, 1), 100), point(LocalDate.of(2024, 1, 2), 200))

        val bytes = EquityCurveImage.render(points, width = 300, height = 150)
        val image = ImageIO.read(new ByteArrayInputStream(bytes))

        image.getWidth must_== 300
        image.getHeight must_== 150
      }
    }
  }
}
