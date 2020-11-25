package viper.server.vsi

import akka.actor.{Actor, PoisonPill, Props}
import akka.stream.scaladsl.SourceQueueWithComplete

// --- Actor: MessageActor ---

object QueueActor {
  def props(queue: SourceQueueWithComplete[Envelope]): Props = Props(new QueueActor(queue))
}

class QueueActor(queue: SourceQueueWithComplete[Envelope]) extends Actor {

  override def receive: PartialFunction[Any, Unit] = {
    case TaskProtocol.BackendReport(msg) =>
      val offer_status = queue.offer(msg)
      sender() ! offer_status
    case TaskProtocol.FinalBackendReport(_) =>
      queue.complete()
      self ! PoisonPill
    case _ =>
  }
}
