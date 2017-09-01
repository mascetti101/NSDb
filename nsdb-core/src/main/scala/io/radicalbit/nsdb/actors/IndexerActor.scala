package io.radicalbit.nsdb.actors

import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, Props}
import cats.data.Validated.{Invalid, Valid}
import io.radicalbit.nsdb.actors.NamespaceDataActor.commands._
import io.radicalbit.nsdb.actors.NamespaceDataActor.events._
import io.radicalbit.nsdb.common.protocol.BitOut
import io.radicalbit.nsdb.coordinator.ReadCoordinator.{GetMetrics, MetricsGot}
import io.radicalbit.nsdb.coordinator.WriteCoordinator.MetricDropped
import io.radicalbit.nsdb.coordinator.{ReadCoordinator, WriteCoordinator}
import io.radicalbit.nsdb.index.TimeSeriesIndex
import io.radicalbit.nsdb.statement.StatementParser
import io.radicalbit.nsdb.statement.StatementParser.{ParsedAggregatedQuery, ParsedDeleteQuery, ParsedSimpleQuery}
import org.apache.lucene.index.IndexNotFoundException
import org.apache.lucene.store.FSDirectory

import scala.util.{Failure, Success, Try}

class IndexerActor(basePath: String, namespace: String) extends Actor with ActorLogging {
  import scala.collection.mutable

  private val statementParser = new StatementParser()

  private val indexes: mutable.Map[String, TimeSeriesIndex] = mutable.Map.empty

  private def getIndex(metric: String) =
    indexes.getOrElse(metric, {
      val path     = FSDirectory.open(Paths.get(basePath, namespace, metric))
      val newIndex = new TimeSeriesIndex(path)
      indexes += (metric -> newIndex)
      newIndex
    })

  private def handleQueryResults(metric: String, out: Try[Seq[BitOut]]) = {
    out match {
      case Success(docs) =>
        log.debug("found {} records", docs.size)
        sender() ! ReadCoordinator.SelectStatementExecuted(namespace = namespace, metric = metric, docs)
      case Failure(_: IndexNotFoundException) =>
        log.debug("index not found")
        sender() ! ReadCoordinator.SelectStatementExecuted(namespace = namespace, metric = metric, Seq.empty)
      case Failure(ex) =>
        log.error(ex, "select statement failed")
        sender() ! ReadCoordinator.SelectStatementFailed(ex.getMessage)
    }
  }

  override def receive: Receive = {

    case msg @ GetMetrics(namespace) =>
      sender() ! MetricsGot(namespace, indexes.keys.toSeq)
    case AddRecord(ns, metric, record) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      val w               = index.write(record)
      writer.flush()
      writer.close()
      w match {
        case Valid(r)   => sender ! RecordAdded(ns, metric, record)
        case Invalid(l) => sender ! RecordRejected(ns, metric, record, l.toList)
      }
    case AddRecords(ns, metric, records) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      records.foreach(index.write)
      writer.flush()
      writer.close()
      sender ! RecordsAdded(ns, metric, records)
    case DeleteRecord(ns, metric, record) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      index.delete(record)
      writer.flush()
      writer.close()
      sender ! RecordDeleted(ns, metric, record)
    case DeleteMetric(ns, metric) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      index.deleteAll()
      writer.close()
      sender ! MetricDeleted(ns, metric)
    case DeleteAllMetrics(ns) =>
      indexes.foreach {
        case (_, index) =>
          implicit val writer = index.getWriter
          index.deleteAll()
          writer.close()
      }
      sender ! AllMetricsDeleted(ns)
    case GetCount(ns, metric) =>
      val index = getIndex(metric)
      val hits  = index.timeRange(0, Long.MaxValue)
      sender ! CountGot(ns, metric, hits.size)
    case ReadCoordinator.ExecuteSelectStatement(statement, schema) =>
      statementParser.parseStatement(statement, schema) match {
        case Success(ParsedSimpleQuery(_, metric, q, limit, fields, sort)) =>
          handleQueryResults(metric, Try(getIndex(metric).query(q, limit, sort)))
        case Success(ParsedAggregatedQuery(_, metric, q, collector)) =>
          handleQueryResults(metric, Try(getIndex(metric).query(q, collector)))
        case Failure(ex) => sender() ! ReadCoordinator.SelectStatementFailed(ex.getMessage)
        case _           => sender() ! ReadCoordinator.SelectStatementFailed("Not a select statement.")
      }
    case WriteCoordinator.ExecuteDeleteStatement(statement) =>
      statementParser.parseStatement(statement) match {
        case Success(ParsedDeleteQuery(_, metric, q)) =>
          val index            = getIndex(metric)
          implicit val writer  = index.getWriter
          val numberOfDeletion = index.delete(q)
          writer.close()
          sender() ! WriteCoordinator.DeleteStatementExecuted(namespace = namespace, metric = metric, numberOfDeletion)
        case Failure(ex) =>
          sender() ! WriteCoordinator.DeleteStatementFailed(namespace = namespace,
                                                            metric = statement.metric,
                                                            ex.getMessage)
      }
    case WriteCoordinator.DropMetric(_, metric) =>
      indexes
        .get(metric)
        .fold {
          sender() ! MetricDropped(namespace, metric)
        } { index =>
          implicit val writer = index.getWriter
          index.deleteAll()
          writer.close()
          indexes -= metric
          sender() ! MetricDropped(namespace, metric)
        }
  }
}

object IndexerActor {

  def props(basePath: String, namespace: String): Props = Props(new IndexerActor(basePath, namespace: String))

}
