// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

object FetchArtifact {
  def apply(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    Left("Firtool not found on system: building firtool is not supported in Scala 3")
  }
}
