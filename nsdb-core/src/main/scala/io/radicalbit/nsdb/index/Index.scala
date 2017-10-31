package io.radicalbit.nsdb.index

import cats.data.Validated.{invalidNel, valid}
import io.radicalbit.nsdb.index.lucene.AllGroupsAggregationCollector
import io.radicalbit.nsdb.validation.Validation.{FieldValidation, WriteValidation}
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.{Document, Field, LongPoint, StringField}
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig}
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search._
import org.apache.lucene.store.BaseDirectory

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

trait Index[T] {
  def directory: BaseDirectory

  def _keyField: String

  private lazy val searcherManager: SearcherManager = new SearcherManager(directory, null)

  def getWriter = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer))

  def getSearcher: IndexSearcher = searcherManager.acquire()

  def release(searcher: IndexSearcher) = {
    searcherManager.maybeRefreshBlocking()
    searcherManager.release(searcher)
  }

  def validateRecord(data: T): FieldValidation
  def toRecord(document: Document, fields: Seq[String]): T

  def write(fields: Seq[Field])(implicit writer: IndexWriter): WriteValidation = {
    val doc = new Document
    fields.foreach(doc.add)
    Try(writer.addDocument(doc)) match {
      case Success(id) => valid(id)
      case Failure(ex) => invalidNel(ex.getMessage)
    }
  }

  protected def write(data: T)(implicit writer: IndexWriter): WriteValidation

  def delete(data: T)(implicit writer: IndexWriter): Unit

  def delete(query: Query)(implicit writer: IndexWriter): Long = {
    val reader   = DirectoryReader.open(directory)
    val searcher = new IndexSearcher(reader)
    val hits     = searcher.search(query, Int.MaxValue)
    (0 until hits.totalHits).foreach { _ =>
      writer.deleteDocuments(query)
    }
    writer.forceMergeDeletes(true)
    hits.totalHits
  }

  def deleteAll()(implicit writer: IndexWriter): Unit = {
    writer.deleteAll()
    writer.flush()
  }

  private def executeQuery(searcher: IndexSearcher, query: Query, limit: Int, sort: Option[Sort]) = {
    val docs: ListBuffer[Document] = ListBuffer.empty
    val hits =
      sort.fold(searcher.search(query, limit).scoreDocs)(sort => searcher.search(query, limit, sort).scoreDocs)
    (0 until hits.length).foreach { i =>
      val doc = searcher.doc(hits(i).doc)
      docs += doc
    }
    docs.toList
  }

  private[index] def rawQuery(query: Query, limit: Int, sort: Option[Sort])(
      implicit searcher: IndexSearcher): Seq[Document] = {
    executeQuery(searcher, query, limit, sort)
  }

  private[index] def rawQuery(query: Query, collector: AllGroupsAggregationCollector)(
      implicit searcher: IndexSearcher): Seq[Document] = {
    searcher.search(query, collector)
    collector.getGroupMap.map {
      case (g, v) =>
        val doc = new Document
        doc.add(new StringField(collector.groupField, g, Store.NO))
        doc.add(new LongPoint(collector.aggField, v))
        doc.add(new LongPoint(_keyField, 0))
        doc
    }.toSeq
  }

  def query(query: Query, fields: Seq[String], limit: Int, sort: Option[Sort])(
      implicit searcher: IndexSearcher): Seq[T] = {
    rawQuery(query, limit, sort).map(d => toRecord(d, fields))
  }

  def query(query: Query, collector: AllGroupsAggregationCollector)(implicit searcher: IndexSearcher): Seq[T] = {
    rawQuery(query, collector).map(d => toRecord(d, Seq.empty))
  }

  def query(field: String, queryString: String, fields: Seq[String], limit: Int, sort: Option[Sort] = None): Seq[T] = {
    val reader   = DirectoryReader.open(directory)
    val searcher = new IndexSearcher(reader)
    val parser   = new QueryParser(field, new StandardAnalyzer())
    val query    = parser.parse(queryString)
    executeQuery(searcher, query, limit, sort).map(d => toRecord(d, fields))
  }

  def getAll()(implicit searcher: IndexSearcher): Seq[T] = {
    Try { query(new MatchAllDocsQuery(), Seq.empty, Int.MaxValue, None) } match {
      case Success(docs: Seq[T]) => docs
      case Failure(_)            => Seq.empty
    }
  }
}
