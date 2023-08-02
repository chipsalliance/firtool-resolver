
// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import scala.util.Try
import java.io.File
import java.net.URLClassLoader

import dev.dirs.{BaseDirectories, ProjectDirectories, UserDirectories}
import scribe.Logger
import coursier._

final case class FirtoolBinary(path: File, version: String)

/** Resolve a firtool binary
  *
  * The basic algorithm is as follows
  *   - Check for firtool on the PATH, note any version mismatch
  *   - Check for firtool in resources, note any version mismtach
  *     - If found, check if already extracted, extract if not
  *   - If none of the above found, use coursier to fetch firtool and check resources again
  */
object Resolve {

  private val operatingSystem: String = {
    // Mac OS X
    // Linux
    System.getProperty("os.name").toLowerCase
  }
  private val architecture: String = {
    // amd64, x86_64
    System.getProperty("os.arch").toLowerCase
  }

  // Important constants
  private def binaryName = "firtool"
  private val VersionRegex = """^CIRCT firtool-(\S+)$""".r
  private def baseDir: String = {
    val path = sys.env.getOrElse("FIRTOOL_CACHE", ProjectDirectories.from("org", "llvm", "firtool").cacheDir)
    new File(path).getAbsolutePath()
  }

  private def checkPATH(logger: Logger): Either[String, FirtoolBinary] = {
    logger.debug("Checking PATH for firtool")

    val proc = os.proc(binaryName, "--version")
    logger.debug(s"Running: ${proc.commandChunks.mkString(" ")}")

    val resultOpt = Try(proc.call(mergeErrIntoOut=true, check=false)).toOption
    resultOpt match {
      case None =>
        val msg = "Firtool binary not on PATH"
        logger.debug(msg)
        Left(msg)
      case Some(result) =>
        val version = result.out.lines().collectFirst { case VersionRegex(v) => v }

        if (result.exitCode != 0 || version.isEmpty) {
          logger.debug(s"Exit Code: ${result.exitCode}")
          logger.debug(s"Output: '${result.out.text()}'")
          Left("Failed to determine firtool binary version")
        } else {
          val v = version.get
          val absPath = (new File(binaryName)).getAbsolutePath
          Right(FirtoolBinary(new File(absPath), v))
        }
    }
  }

  private def checkResources(classloader: Option[URLClassLoader], logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    logger.debug("Checking resources for firtool")
    val resources = classloader.map(os.resource(_)).getOrElse(os.resource)
    // TODO try-catch this
    val versionOpt = Try(os.read(resources / "firtool" / "version")).toOption
    if (versionOpt.isEmpty) {
      val msg = "firtool not found in resources"
      logger.debug(msg)
      return Left(msg)
    }

    val version = versionOpt.get
    logger.debug(s"Firtool version $version found in resources")

    val topDir = os.Path(baseDir) / version

    val destDir = topDir / "bin"
    val destBin = destDir / "firtool"
    val destFile: File = destBin.toIO

    // Check if binary already exists
    if (destFile.isFile()) {
      logger.debug(s"Firtool binary $destFile already exists")
    } else {
      // Copy
      logger.debug(s"Copying firtool from resources to $destFile")
      val resourceBin = resources / "firtool" / "bin" / "firtool"
      val result = Try {
        os.makeDir.all(destDir)
        os.write(destBin, os.read.stream(resourceBin), perms = "rwxrwxr-x")
      }
      if (result.isFailure) {
        val msg = s"Copying firtool failed with ${result.failed.get}"
        logger.debug(msg)
        return Left(msg)
      }
    }
    Right(FirtoolBinary(destFile, version))
  }

  private def fetchArtifact(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    val org = Organization("org.chipsalliance")
    val module = Module(org, ModuleName("llvmFirtoolNative-macos-x86"), Map())
    val dep = Dependency(module, defaultVersion + "-SNAPSHOT") // TODO delete -SNAPSHOT
    logger.debug(s"Attempting to fetch ${dep.module}:${dep.version}")
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
    val jar = resolution.get.head
    logger.debug(s"Successfully fetched $jar")


    logger.debug(s"Loading $jar to search its resources")
    val classloader = new URLClassLoader(Array(jar.toURL))
    checkResources(Some(classloader), logger, defaultVersion)
  }

  def apply(defaultVersion: String): Either[String, FirtoolBinary] = apply(Logger("FirtoolResolver"), defaultVersion)

  def apply(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {

    // TODO if firtool is found but version lookup fails, should we just return error?
    for {
      msg1 <- checkPATH(logger).left
      msg2 <- checkResources(None, logger, defaultVersion).left
      msg3 <- fetchArtifact(logger, defaultVersion).left
    } yield {
      s"Failed to fetch firtool:\n$msg1\n$msg2\n$msg3"
    }
  }
}


object Main extends App {
  scribe.Logger.root
      .withMinimumLevel(scribe.Level.Debug)
      .replace()
  println(Resolve("1.48.0"))
}
