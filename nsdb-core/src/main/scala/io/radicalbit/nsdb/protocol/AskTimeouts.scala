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

package io.radicalbit.nsdb.protocol

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import com.typesafe.config.ConfigFactory

object AskTimeouts {

  private val config = ConfigFactory
    .parseFile(Paths.get(System.getProperty("confDir"), "cluster.conf").toFile)
    .resolve()
    .withFallback(ConfigFactory.load("cluster.conf"))

  val writeTimeout: Timeout =
    Timeout(config.getDuration("nsdb.write.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  val readTimeout: Timeout =
    Timeout(config.getDuration("nsdb.read.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  val accumulatorTimeout: Timeout =
    Timeout((writeTimeout.duration.length.toDouble / 100 * 20).toLong, TimeUnit.SECONDS)
  val commitLogTimeout: Timeout = Timeout((writeTimeout.duration.length.toDouble / 100 * 20).toLong, TimeUnit.SECONDS)
  val schemaTimeout: Timeout    = Timeout((writeTimeout.duration.length.toDouble / 100 * 20).toLong, TimeUnit.SECONDS)
  val metadataTimeout: Timeout  = Timeout((writeTimeout.duration.length.toDouble / 100 * 20).toLong, TimeUnit.SECONDS)
  val dataTimeout: Timeout      = Timeout((writeTimeout.duration.length.toDouble / 100 * 20).toLong, TimeUnit.SECONDS)

  val replicatedMetadataCacheTimeout: Timeout =
    Timeout((metadataTimeout.duration.length.toDouble / 100 * 80).toLong, TimeUnit.SECONDS)

}
