/*
 * Copyright 2018 Radicalbit S.r.l.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.radicalbit.nsdb.cluster.coordinator

import akka.actor.ActorRef
import io.radicalbit.nsdb.commit_log.CommitLogWriterActor._
import io.radicalbit.nsdb.commit_log.{CommitLogWriterActor, RollingCommitLogFileWriter}
import io.radicalbit.nsdb.util.ActorPathLogging

import scala.collection.mutable

/**
  * Actor whose purpose is to handle writes on commit-log files delegating the action to writers implementing
  * [[CommitLogWriterActor]] trait .
  * In this implementation a writer is instantiated for each tuple (database, namespace).
  */
class CommitLogCoordinator extends ActorPathLogging {

  private val commitLoggerWriters: mutable.Map[String, ActorRef] = mutable.Map.empty

  private def getWriter(db: String, namespace: String): ActorRef = {
    commitLoggerWriters.getOrElse(
      s"$db-$namespace", {
        val commitLogWriter =
          context.actorOf(RollingCommitLogFileWriter.props(db, namespace), s"commit-log-writer-$db-$namespace")
        commitLoggerWriters += (s"$db-$namespace" -> commitLogWriter)
        commitLogWriter
      }
    )
  }

  def receive: Receive = {
    case msg @ WriteToCommitLog(db, namespace, _, _, _, _) =>
      getWriter(db, namespace).forward(msg)
    case _ =>
      log.error("UnexpectedMessage")
  }
}
