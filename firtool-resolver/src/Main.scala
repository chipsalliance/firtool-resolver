
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
  *   - Check for firtool on the FIRTOOL_PATH, note any version mismatch
  *   - Check for firtool in resources, note any version mismtach
  *     - If found, check if already extracted, extract if not
  *   - If none of the above found, use coursier to fetch firtool and check resources again
  */
object Resolve {

  private lazy val operatingSystem: Either[String, String] = {
    // Mac OS X
    // Linux
    val osName = System.getProperty("os.name")
    val name = osName.toLowerCase
    if (name.startsWith("win")) Right("windows")
    else if (name.startsWith("mac")) Right("macos")
    else if (name.startsWith("linux")) Right("linux")
    else Left(s"Unsupported OS: $osName")
  }
  private lazy val architecture: Either[String, String] = {
    // amd64, x86_64
    // aarch64
    val osArch = System.getProperty("os.arch")
    val arch = osArch.toLowerCase
    if (arch == "amd64" || arch == "x86_64") Right("x64")
    else if (arch == "aarch64") Right("aarch64")
    else Left(s"Unsupported architecture: $osArch")
  }

  // Important constants
  private def groupId = "org.chipsalliance"
  private def artId = "llvm-firtool"
  // Use x64 for Apple Silicon
  private def appleSiliconFixup(logger: Logger, os: String, arch: String): String = {
    if (os == "macos" && arch == "aarch64") {
      logger.debug("Using x64 architecture for Apple silicon")
      "x64"
    } else {
      arch
    }
  }
  private def determinePlatform(logger: Logger): Either[String, String] =
    for {
      os <- operatingSystem
      _arch <- architecture
      arch = appleSiliconFixup(logger, os, _arch)
    } yield s"$os-$arch"
  private def binaryName = "firtool"
  private val VersionRegex = """^CIRCT firtool-(\S+)$""".r

  private def cacheDir: String = {
    val path = sys.env.getOrElse("FIRTOOL_CACHE", ProjectDirectories.from("", groupId, artId).cacheDir)
    new File(path).getAbsolutePath()
  }

  private def checkPath(logger: Logger): Either[String, FirtoolBinary] = {
    logger.debug("Checking FIRTOOL_PATH for firtool")

    // TODO make this function more consistent between return and matching
    val firtoolPathOpt = sys.env.get("FIRTOOL_PATH")
    if (firtoolPathOpt.isEmpty) {
      val msg = "FIRTOOL_PATH not set"
      logger.debug(msg)
      return Left(msg)
    }

    val firtoolPath = os.Path(firtoolPathOpt.get, os.pwd)
    if (!os.exists(firtoolPath)) {
      val msg = s"FIRTOOL_PATH ($firtoolPath) does not exist"
      logger.debug(msg)
      return Left(msg)
    }

    val binary = firtoolPath / binaryName

    val proc = os.proc(binary, "--version")
    logger.debug(s"Running: ${proc.commandChunks.mkString(" ")}")

    val resultOpt = Try(proc.call(mergeErrIntoOut=true, check=false)).toOption
    resultOpt match {
      case None =>
        val msg = "Firtool binary not on FIRTOOL_PATH"
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
          Right(FirtoolBinary(binary.toIO, v))
        }
    }
  }

  private def checkResources(classloader: Option[URLClassLoader], logger: Logger): Either[String, FirtoolBinary] = {
    val platform = determinePlatform(logger)
    logger.debug("Checking resources for firtool")
    if (platform.isLeft) {
      logger.debug(platform.merge)
      return Left(platform.merge) // Help out the type system
    }
    val resources = classloader.map(os.resource(_)).getOrElse(os.resource)
    val baseDir = resources / groupId / artId
    val artDir = baseDir / platform.toOption.get // checked above
    val versionFile = baseDir / "project.version"
    val versionOpt = Try(os.read(versionFile)).toOption
    if (versionOpt.isEmpty) {
      val msg = s"firtool version not found in resources ($versionFile)"
      logger.debug(msg)
      return Left(msg)
    }

    val version = versionOpt.get
    logger.debug(s"Firtool version $version found in resources")

    val topDir = os.Path(cacheDir) / version

    val destDir = topDir / "bin"
    val destBin = destDir / "firtool"
    val destFile: File = destBin.toIO

    // Check if binary already exists
    if (destFile.isFile()) {
      logger.debug(s"Firtool binary $destFile already exists")
    } else {
      // Copy
      logger.debug(s"Copying firtool from resources to $destFile")
      val resourceBin = artDir / "bin" / "firtool"
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
    val org = Organization(groupId)
    val module = Module(org, ModuleName(s"$artId"), Map())
    val dep = Dependency(module, defaultVersion)
    logger.debug(s"Attempting to fetch ${dep.module}:${dep.version}")
    val resolution = Try {
      coursier.Fetch()
      .addDependencies(dep)
      // TODO classifier doesn't seem to work...
      //.withClassifier(Classifier(platform))
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
    val classloader = new URLClassLoader(Array(jar.toURL))
    checkResources(Some(classloader), logger)
  }

  /** Lookup firtool binary
    *
    * @param defaultVersion fallback version to fetch if not found in FIRTOOL_PATH nor on classpath
    * @param verbose print verbose logging information
    * @return Either an error message or the firtool binary
    */
  def apply(defaultVersion: String, verbose: Boolean = false): Either[String, FirtoolBinary] = {
    val base = Logger("FirtoolResolver")
    val logger = if (verbose) base.withMinimumLevel(scribe.Level.Debug) else base
    apply(logger, defaultVersion)
  }

  def apply(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    for {
      msg1 <- checkPath(logger).left
      msg2 <- checkResources(None, logger).left
      msg3 <- fetchArtifact(logger, defaultVersion).left
    } yield {
      s"Failed to fetch firtool:\n$msg1\n$msg2\n$msg3"
    }
  }
}


object Main extends App {
  def usageErr(): Nothing = {
    System.err.println("USAGE: firtool-resolver [-v] <version>")
    sys.exit(1)
  }
  // Trying to avoid argument parser dependency if possible, just contributes to dependency hell
  val (verbose, version) =
    args.size match {
      case 1 =>
        (false, args(0))
      case 2 =>
        if (args(0) != "-v") {
          usageErr()
        }
        (true, args(1))
      case _ =>
        usageErr()
    }
  Resolve(version, verbose = verbose) match {
    case Right(bin) => println(bin.path)
    case Left(err) =>
      System.err.println(err)
      sys.exit(1)
  }
}
