package com.charlibot.pubsubexample

import cats.effect.{ExitCode, IO, IOApp}
import com.charlibot.pubsub.PubsubSubscriber
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {

  implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("pubsub-example")

  override def run(args: List[String]): IO[ExitCode] = {
    val subscription =
      args.headOption.getOrElse(throw new RuntimeException("Missing subscription argument!"))

    PubsubSubscriber
      .createChannelAndSubscribe[IO](subscription)
      .evalTap(msg => IO.println(msg.pubsubMessage.data.toStringUtf8))
      .foreach(_.ack)
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
