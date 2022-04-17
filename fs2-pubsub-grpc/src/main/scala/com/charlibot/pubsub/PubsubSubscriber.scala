package com.charlibot.pubsub

import cats.effect.Resource
import cats.syntax.all._
import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.{Dispatcher, Queue}
import com.google.auth.oauth2.GoogleCredentials
import fs2._
import com.google.pubsub.v1.pubsub.{
  PubsubMessage,
  ReceivedMessage,
  StreamingPullRequest,
  StreamingPullResponse,
  SubscriberFs2Grpc
}
import fs2.grpc.client.ClientOptions
import fs2.grpc.syntax.all._
import io.grpc.auth.MoreCallCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.{ManagedChannel, Metadata, Status, StatusRuntimeException}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.DurationInt

class PubsubSubscriber[F[_]: Temporal: Logger](
    subscriberFs2Grpc: SubscriberFs2Grpc[F, Unit]
) {
  import PubsubSubscriber._

  /**
   * Subscribes to a Pub/Sub subscription using [[https://cloud.google.com/pubsub/docs/pull#streamingpull Streaming Pull]].
   * @param subscription Subscription to receive messages for.
   *                     Must have the form 'projects/{project}/subscriptions/{sub}'.
   * @return An [[Stream]] of [[Message]]s delivered over GRPC.
   */
  def streamingPull(subscription: String): Stream[F, Message[F]] =
    for {
      ackQueue <- Stream.eval(Queue.unbounded[F, String])
      requestsQueue <- Stream.eval(Queue.unbounded[F, StreamingPullRequest])
      streamingRequest = StreamingPullRequest(
        subscription = subscription,
        streamAckDeadlineSeconds = 30
      )
      streamingResponses = startStreamingPull(streamingRequest, requestsQueue)
      responsesWithAck = streamingResponses
        .concurrently(
          Stream
            .fromQueueUnterminated(ackQueue)
            .groupWithin(1000, 10.seconds)
            .map(ackIds => StreamingPullRequest(ackIds = ackIds.toList))
            .evalMap(requestsQueue.offer)
            .onFinalize(Logger[F].info("Ack queue is closed."))
        )
      message <- responsesWithAck.flatMap { streamingResponse =>
        Stream.emits(streamingResponse.receivedMessages.collect {
          case ReceivedMessage(ackId, Some(message), _, _) =>
            Message(message, ackQueue.offer(ackId))
        })
      }
    } yield message

  private def startStreamingPull(
      subRequest: StreamingPullRequest,
      requestsQueue: Queue[F, StreamingPullRequest]
  ): Stream[F, StreamingPullResponse] =
    for {
      _ <- Stream.eval(requestsQueue.offer(subRequest))
      requests = Stream.fromQueueUnterminated(requestsQueue)
      responses <- subscriberFs2Grpc.streamingPull(requests, ()).handleErrorWith { t =>
        if (shouldRetry(t)) {
          Stream.eval(
            Logger[F]
              .debug(s"Retrying subscription: ${subRequest.subscription} due to ${t.getMessage}.")
          ) *>
            startStreamingPull(subRequest, requestsQueue)
        } else {
          Stream
            .eval(
              Logger[F].error(t)(s"Not retrying subscription named ${subRequest.subscription}.")
            ) *>
            Stream.raiseError[F](t)
        }

      }
    } yield responses

  // Copy how to handle the status codes from the java-pubsub client:
  // https://github.com/googleapis/java-pubsub/blob/3709b2eb842ac014203680c1af5b081c7a952e2f/google-cloud-pubsub/src/main/java/com/google/cloud/pubsub/v1/StatusUtil.java#L28
  import Status.Code._
  private def shouldRetry(t: Throwable): Boolean = t match {
    case t: StatusRuntimeException =>
      t.getStatus.getCode match {
        case DEADLINE_EXCEEDED | INTERNAL | CANCELLED | RESOURCE_EXHAUSTED | ABORTED | UNKNOWN =>
          true
        case UNAVAILABLE => !t.getStatus.getDescription.contains("Channel shutdown")
        case _           => false
      }
    case _ => false
  }
}

object PubsubSubscriber {

  /**
   * Wraps the Pub/Sub message and acking mechanism.
   * @param pubsubMessage Pub/Sub message.
   * @param ack Computation to ack a message. Note there are no guarantees the message is
   *            successfully acked when the [[ack]] computation completes. The message
   *            may be redelivered at a later time.
   */
  case class Message[F[_]](pubsubMessage: PubsubMessage, ack: F[Unit])

  /**
   * Subscribes and returns a stream of messages.
   *
   * A lot of ceremony must be performed to use the fs2 grpc subscriber, notably setting up the
   * netty channel and configuring the Google credentials. This method will take care of that
   * but the resources cannot be shared with other calls to this method. In general, you should
   * reuse the netty channel and thus the grpc client if subscribing to more than one subscription.
   * Use [[PubsubSubscriber]] directly if that is required.
   */
  def createChannelAndSubscribe[F[_]: Async: Logger](subscription: String): Stream[F, Message[F]] = {
    val pubsubChannelResource: Resource[F, ManagedChannel] =
      NettyChannelBuilder
        .forAddress("pubsub.googleapis.com", 443)
        .resource[F]
    val googleCredentials = GoogleCredentials.getApplicationDefault
    val clientOptions = ClientOptions.default
      .configureCallOptions(callOptions =>
        callOptions.withCallCredentials(
          MoreCallCredentials.from(googleCredentials)
        )
      )
    for {
      dispatcher <- Stream.resource(Dispatcher[F])
      pubsubChannel <- Stream.resource(pubsubChannelResource)
      grpcClient = SubscriberFs2Grpc.client[F, Unit](
        dispatcher,
        pubsubChannel,
        (_: Unit) => new Metadata(),
        clientOptions
      )
      subscriber = new PubsubSubscriber[F](grpcClient)
      message <- subscriber.streamingPull(subscription)
    } yield message
  }
}
