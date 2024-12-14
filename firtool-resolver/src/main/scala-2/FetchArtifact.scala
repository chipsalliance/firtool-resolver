// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import scala.util.{Failure, Success, Try}
import java.net.URLClassLoader
import coursier._
import coursier.core.Extension

object FetchArtifact {
  def apply(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    val platform =
      determinePlatform(logger) match {
        case Left(msg) =>
          logger.debug(msg)
          return Left(msg)
        case Right(name) => name
      }
    // See coursier.parse.DependencyParser to understand how the classifier is added via Publication
    val org = Organization(groupId)
    val module = Module(org, ModuleName(s"$artId"), Map())
    val dep =
      Dependency(module, defaultVersion)
        .withPublication("", Type.empty, Extension.empty, Classifier(platform))
    // One would think there'd be a built-in pretty print like this but there isn't
    //   (coursier.util.Print doesn't include the classifier)
    logger.debug(s"Attempting to fetch ${dep.module}:${dep.version},clasifier=${platform}")

    val resolution = Try {
      coursier.Fetch()
        .addDependencies(dep)
        .run()
    }
    if (resolution.isFailure) {
      val msg = resolution.failed.get.toString + "\n" // Coursier's message is already pretty good
      logger.debug(msg)
      return Left(msg)
    }
    // Head here is dangerous, without the classifier, multiple jars are fetched
    val jar = resolution.get.head
    logger.debug(s"Successfully fetched $jar")


    logger.debug(s"Loading $jar to search its resources")
    val classloader = new URLClassLoader(Array(jar.toURI.toURL))
    checkResources(Some(classloader), logger)
  }
}
