package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.functor.*
import tofu.logging.{LoggedValue, Logging}

object LogFakes {

  trait RecordingLogging extends Logging[IO] {

    /** Every entry written, oldest first: its message, then each value as it would be printed. */
    def entries: IO[List[String]]
  }

  // Keeps what is logged as text, for specs of what must never appear in the logs.
  def recordingLogging: IO[RecordingLogging] =
    Ref.of[IO, List[String]](List.empty).map { entriesRef =>
      new RecordingLogging {
        def write(level: Logging.Level, message: String, values: LoggedValue*): IO[Unit] =
          entriesRef
            .update(_ :+ (message +: values.map(LoggedValue.loggable.logShow)).mkString(" | "))

        override def asLogging: Logging[IO] = this

        def entries: IO[List[String]] = entriesRef.get
      }
    }
}
