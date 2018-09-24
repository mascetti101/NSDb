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

package io.radicalbit.nsdb.commit_log

import java.io.File
import java.nio.file.Paths

import akka.actor.Props
import com.typesafe.config.Config
import io.radicalbit.nsdb.commit_log.RollingCommitLogFileChecker.CheckFiles
import io.radicalbit.nsdb.commit_log.RollingCommitLogFileWriter.fileNameSeparator
import io.radicalbit.nsdb.util.ActorPathLogging
import io.radicalbit.nsdb.util.Config.{CommitLogDirectoryConf, CommitLogSerializerConf, getString}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object RollingCommitLogFileChecker {
  def props(db: String, namespace: String, metric: String) =
    Props(new RollingCommitLogFileChecker(db, namespace, metric))

  case class CheckFiles(actualFile: File)
}

class RollingCommitLogFileChecker(db: String, namespace: String, metric: String) extends ActorPathLogging {

  implicit val config: Config = context.system.settings.config

  private val directory       = getString(CommitLogDirectoryConf)
  private val serializerClass = getString(CommitLogSerializerConf)

  implicit val serializer: CommitLogSerializer =
    Class.forName(serializerClass).newInstance().asInstanceOf[CommitLogSerializer]

  private def isOlder(fileName: String, actualFileName: String): Boolean = {
    fileName.split(fileNameSeparator).toList.last.toInt < actualFileName.split(fileNameSeparator).toList.last.toInt
  }

  override def receive: Receive = {
    case CheckFiles(actualFile) =>
      log.info(s"Received commitlog check for actual file : ${actualFile.getName}")
      val existingOldFileNames: List[String] = Option(Paths.get(directory).toFile.list())
        .map(_.toSet)
        .getOrElse(Set.empty)
        .filter(name =>
          name.contains(s"$db$fileNameSeparator$namespace$fileNameSeparator$metric") && isOlder(name,
                                                                                                actualFile.getName))
        .toList
        .sortBy(_.split(fileNameSeparator).toList.last.toInt)

      log.info(s"Old files to be checked: $existingOldFileNames")

      import CommitLogFile._

      val pendingOutdatedEntries: mutable.Map[File, ListBuffer[Int]] = mutable.Map.empty

      existingOldFileNames.foreach(fileName => {
        val processedFile                            = new File(s"$directory/$fileName")
        val (pendingEntries, closedEntries) = processedFile.checkPendingEntries

        pendingOutdatedEntries += (processedFile -> pendingEntries.to[ListBuffer])

        closedEntries.foreach { closedEntry =>
          pendingOutdatedEntries.foreach {
            case (file, pending) =>
              if (pending.toList.contains(closedEntry)){
                log.debug(s"removing entry: $closedEntry in file ${file.getName} processing file: $fileName")
                pending -= closedEntry
              }
              if (pending.isEmpty) {
                pendingOutdatedEntries -= file
                log.info(s"deleting file: ${file.getName}")
                file.delete()
              } else {
                pendingOutdatedEntries(file) = pending
              }

              log.debug(s"pending entries for file: ${file.getName} are : ${pending.size}")
          }
        }

        if (pendingEntries.isEmpty) {
          processedFile.delete()
        }

      })
    case msg =>
      log.error(s"Unexpected message: $msg")
  }
}
