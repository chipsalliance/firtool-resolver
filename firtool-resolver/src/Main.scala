// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import scala.util.{Failure, Success, Try}
import scala.collection.mutable
import scala.io.Source
import java.io.File
import java.nio.file.{Path, Paths, Files, StandardCopyOption}
import java.net.URLClassLoader
import scala.sys.process._

import dev.dirs.{BaseDirectories, ProjectDirectories, UserDirectories}
import coursier._
import coursier.core.Extension

final case class FirtoolBinary(path: File, version: String)

/** Resolve a firtool binary
  *
  * The basic algorithm is as follows
  *   - Check for firtool on the CHISEL_FIRTOOL_PATH, note any version mismatch
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
    val path = sys.env.getOrElse("CHISEL_FIRTOOL_CACHE", ProjectDirectories.from("", groupId, artId).cacheDir)
    new File(path).getAbsolutePath()
  }

  private def firtoolBin(version: String): Path = {
    val topDir = Paths.get(cacheDir).resolve(version)

    val destDir = topDir.resolve("bin")
    destDir.resolve("firtool")
  }

  // Nested Either is weird but represents unrecoverable vs. recoverable errors
  // Left(...) - unrecoverable error
  // Right(Left(...)) - recoverable error
  // Right(Right(...)) - success
  private def checkFirtoolPath(logger: Logger): Either[String, Either[String, FirtoolBinary]] = {
    def Recoverable(msg: String): Either[String, Either[String, FirtoolBinary]] = Right(Left(msg))
    def Unrecoverable(msg: String): Either[String, Either[String, FirtoolBinary]] = Left(msg)

    logger.debug("Checking CHISEL_FIRTOOL_PATH for firtool")

    // TODO make this function more consistent between return and matching
    val firtoolPathOpt = sys.env.get("CHISEL_FIRTOOL_PATH")
    if (firtoolPathOpt.isEmpty) {
      val msg = "CHISEL_FIRTOOL_PATH not set"
      logger.debug(msg)
      return Recoverable(msg)
    }

    val firtoolPath = Paths.get(firtoolPathOpt.get).toAbsolutePath
    if (!Files.exists(firtoolPath)) {
      val msg = s"CHISEL_FIRTOOL_PATH ($firtoolPath) does not exist"
      logger.debug(msg)
      return Unrecoverable(msg)
    }

    val binary = firtoolPath.resolve(binaryName)

    val cmd = Seq(binary.toString, "--version")
    logger.debug(s"Running: ${cmd.mkString(" ")}")

    val stdouterr = mutable.ArrayBuffer.empty[String]
    val procLogger = ProcessLogger(stdouterr += _)

    val result = Try(cmd.!(procLogger))
    result match {
      case Failure(e) =>
        val msg = e.getMessage
        logger.debug(msg)
        Unrecoverable(msg)
      case Success(exitCode) =>
        val out = stdouterr.mkString("\n")
        val version = stdouterr.collectFirst { case VersionRegex(v) => v }

        if (exitCode != 0) {
          val msg =
            s"""|Unable to run firtool binary ($binary):
                |  Exit Code: ${exitCode}
                |  Output: '${out}
                |""".stripMargin
          logger.debug(msg)
          Unrecoverable(msg)
        } else {
          // If version regex fails, that's fine
          val v = version.getOrElse("<unknown>")
          Right(Right(FirtoolBinary(binary.toFile, v)))
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
    val resourceLoader = classloader.getOrElse(this.getClass.getClassLoader)

    val baseDir = s"$groupId/$artId"
    val artDir = s"$baseDir/${platform.toOption.get}" // checked above
    val versionFile = resourceLoader.getResource(s"$baseDir/project.version")
    val versionOpt = Try(Source.fromURL(versionFile).mkString).toOption
    if (versionOpt.isEmpty) {
      val msg = s"firtool version not found in resources ($versionFile)"
      logger.debug(msg)
      return Left(msg)
    }

    val version = versionOpt.get
    logger.debug(s"Firtool version $version found in resources")

    val destBin = firtoolBin(version)
    val destFile: File = destBin.toFile

    // Check if binary already exists
    if (destFile.isFile()) {
      logger.debug(s"Firtool binary $destFile already exists")
    } else this.synchronized {
      // Copy
      // This can be executed by many threads or processes at a time so use atomic move
      logger.debug(s"Copying firtool from resources to $destFile")
      val resourceBin = resourceLoader.getResourceAsStream(s"$artDir/bin/firtool")
      val result = Try {
        val dir = destBin.getParent
        Files.createDirectories(dir)
        // Use temporary file for multi-thread or multi-process safety
        val tmp = Files.createTempFile(dir, destBin.getFileName.toString, ".tmp")
        logger.debug(s"Created temporary file $tmp")
        Files.copy(resourceBin, tmp, StandardCopyOption.REPLACE_EXISTING)
        // Use java.io.File APIs to support Windows
        tmp.toFile.setWritable(true)
        tmp.toFile.setReadable(true)
        tmp.toFile.setExecutable(true)
        logger.debug(s"Atomically moving $tmp to $destBin")
        Files.move(tmp, destBin, StandardCopyOption.ATOMIC_MOVE)
      }
      if (result.isFailure) {
        val msg = s"Copying firtool failed with ${result.failed.get}"
        logger.debug(msg)
        return Left(msg)
      }
    }
    Right(FirtoolBinary(destFile, version))
  }

  private def checkInstalled(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    val destFile: File = firtoolBin(defaultVersion).toFile
    if (destFile.isFile) {
      logger.debug(s"Firtool binary with default version ($defaultVersion) $destFile already exists")
      Right(FirtoolBinary(destFile, defaultVersion))
    } else {
      val msg = s"Firtool binary with default version ($defaultVersion) does not already exist"
      logger.debug(msg)
      Left(msg)
    }
  }

  private def fetchArtifact(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
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

  /** Lookup firtool binary
    *
    * @param defaultVersion fallback version to fetch if not found in CHISEL_FIRTOOL_PATH nor on classpath
    * @param verbose print verbose logging information
    * @return Either an error message or the firtool binary
    */
  def apply(defaultVersion: String, verbose: Boolean = false): Either[String, FirtoolBinary] = {
    val logger = if (verbose) Logger.debug else Logger.warn
    apply(logger, defaultVersion)
  }

  def apply(logger: Logger, defaultVersion: String): Either[String, FirtoolBinary] = {
    for {
      // checkFirtoolPath has both recoverable and unrecoverable errors
      // First <- does not have .left projection so that unrecoverable errors short-circuit
      res <- checkFirtoolPath(logger)
      // Left projections allow us to short-circuit on any Right, and otherwise aggregate errors
      // returned in Left
      msg1 <- res.left
      msg2 <- checkResources(None, logger).left
      msg3 <- checkInstalled(logger, defaultVersion).left
      msg4 <- fetchArtifact(logger, defaultVersion).left
    } yield {
      List("Failed to fetch firtool:", msg1, msg2, msg3, msg4).mkString("\n")
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
