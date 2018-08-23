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

package io.radicalbit.nsdb.cluster.serialization

import akka.serialization.SerializerWithStringManifest
import io.radicalbit.nsdb.common.bit.Bit
import io.radicalbit.nsdb.common.location.Location

class ProtobufSerializer extends SerializerWithStringManifest {

  private final val BitManifest: String      = "Bit"
  private final val LocationManifest: String = "Location"

  override def identifier: Int = 11110000

  override def manifest(o: AnyRef): String = o.getClass.getSimpleName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case b: Bit =>
      b.toByteArray
    case l: Location =>
      l.toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case BitManifest      => Bit.parseFrom(bytes)
    case LocationManifest => Location.parseFrom(bytes)
  }
}
