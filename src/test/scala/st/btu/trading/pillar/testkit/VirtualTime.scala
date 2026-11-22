package st.btu.trading.pillar.testkit

import cats.effect.IO
import cats.effect.testkit.TestControl

import scala.concurrent.duration.FiniteDuration

/** Runs a program on the virtual clock of `TestControl`: a sleep takes no real time, and the time
  * the program reads is exactly the time it has slept. An example that measures the time between
  * calls gets the same answer however loaded the machine is.
  */
object VirtualTime {

  // TestControl picks at random among the fibers that are ready together unless it is given a
  // seed: a number, as base64. The same one every time makes every run pick the same way.
  private val Seed = "MTIzNDU="

  /** `program`, run on the virtual clock, which starts at zero. */
  def run[A](program: IO[A]): IO[A] = TestControl.executeEmbed(program, seed = Some(Seed))

  /** The time from each of `times` to the next one, in time order. */
  def gaps(times: List[FiniteDuration]): List[FiniteDuration] = {
    val ordered = times.sorted
    ordered.zip(ordered.drop(1)).map { case (earlier, later) => later - earlier }
  }
}
