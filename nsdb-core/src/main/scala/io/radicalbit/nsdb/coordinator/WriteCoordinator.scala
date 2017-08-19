package io.radicalbit.nsdb.coordinator

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import akka.util.Timeout
import io.radicalbit.commit_log.CommitLogService
import io.radicalbit.nsdb.actors.NamespaceDataActor.commands.AddRecord
import io.radicalbit.nsdb.actors.NamespaceDataActor.events.{RecordAdded, RecordRejected}
import io.radicalbit.nsdb.actors.NamespaceSchemaActor.commands.UpdateSchemaFromRecord
import io.radicalbit.nsdb.actors.NamespaceSchemaActor.events.{SchemaUpdated, UpdateSchemaFailed}
import io.radicalbit.nsdb.actors.PublisherActor.Events.RecordPublished
import io.radicalbit.nsdb.commit_log.CommitLogWriterActor.WroteToCommitLogAck
import io.radicalbit.nsdb.coordinator.WriteCoordinator.{DeleteNamespace, InputMapped, NamespaceDeleted}
import io.radicalbit.nsdb.model.Record

import scala.concurrent.Future

object WriteCoordinator {

  def props(namespaceSchemaActor: ActorRef,
            commitLogService: ActorRef,
            namespaceDataActor: ActorRef,
            publisherActor: ActorRef): Props =
    Props(new WriteCoordinator(namespaceSchemaActor, commitLogService, namespaceDataActor, publisherActor))

  sealed trait WriteCoordinatorProtocol

  case class FlatInput(ts: Long, namespace: String, metric: String, data: Array[Byte]) extends WriteCoordinatorProtocol

  case class MapInput(ts: Long, namespace: String, metric: String, record: Record)    extends WriteCoordinatorProtocol
  case class InputMapped(ts: Long, namespace: String, metric: String, record: Record) extends WriteCoordinatorProtocol

  case class DeleteNamespace(namespace: String)
  case class NamespaceDeleted(namespace: String)
}

class WriteCoordinator(namespaceSchemaActor: ActorRef,
                       commitLogService: ActorRef,
                       namespaceDataActor: ActorRef,
                       publisherActor: ActorRef)
    extends Actor
    with ActorLogging {

  import akka.pattern.ask

  import scala.concurrent.duration._

  implicit val timeout: Timeout = 1 second
  import context.dispatcher

  log.info("WriteCoordinator is ready.")

  override def receive = {
    case WriteCoordinator.MapInput(ts, namespace, metric, record) =>
      log.debug("Received a write request for (ts: {}, metric: {}, record : {})", ts, metric, record)
      (namespaceSchemaActor ? UpdateSchemaFromRecord(namespace, metric, record))
        .flatMap {
          case SchemaUpdated(_, _) =>
            log.debug("Valid schema for the metric {} and the record {}", metric, record)
            (commitLogService ? CommitLogService.Insert(ts = ts, metric = metric, record = record))
              .mapTo[WroteToCommitLogAck]
              .flatMap(ack => {
                publisherActor ! RecordPublished(metric, record)
                (namespaceDataActor ? AddRecord(namespace, ack.metric, ack.record)).mapTo[RecordAdded]
              })
              .map(r =>
                InputMapped(r.record.timestamp, namespace, metric, record.copy(timestamp = r.record.timestamp)))
          case UpdateSchemaFailed(_, _, errs) =>
            log.debug("Invalid schema for the metric {} and the record {}. Error are {}.",
                      metric,
                      record,
                      errs.mkString(","))
            Future(RecordRejected(namespace, metric, record, errs))
        }
        .pipeTo(sender())
    case DeleteNamespace(namespace) =>
      (namespaceDataActor ? DeleteNamespace(namespace))
        .map(e => namespaceSchemaActor ? DeleteNamespace(namespace))
        .mapTo[NamespaceDeleted]
        .pipeTo(sender())

  }
}

trait JournalWriter

class AsyncJournalWriter {}
