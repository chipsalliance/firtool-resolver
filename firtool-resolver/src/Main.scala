// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import scala.util.{Failure, Success, Try}
import scala.collection.mutable
import scala.io.Source
import java.io.File
import java.nio.file.{Path, Paths, Files, StandardCopyOption, StandardOpenOption}
import java.nio.channels.FileChannel
import java.net.URLClassLoader
import scala.sys.process._

import dev.dirs.ProjectDirectories
import coursier._
import coursier.core.Extension

final case class FirtoolBinary(path: File, version: String)

/** Resolve a firtool binary
  *
  * The basic algorithm is as follows
  *   - Check for firtool on the CHISEL_FIRTOOL_PATH, note any version mismatch
  *   - Honor an explicit version override, otherwise check classpath resources
  *   - Reuse the version's cache, or download and verify a GitHub release
  *   - Use Coursier to fetch legacy Maven artifacts as a last resort
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
  // Returns a fallback platform when a platform's native artifacts may not be available.
  // macOS aarch64 falls back to x64 (via Rosetta) for versions that lack native arm64 artifacts.
  private def fallbackPlatform(platform: String): Option[String] = platform match {
    case "macos-aarch64" => Some("macos-x64")
    case _ => None
  }
  private def determinePlatform(logger: Logger): Either[String, String] =
    for {
      os <- operatingSystem
      arch <- architecture
    } yield s"$os-$arch"
  private def binaryName = "firtool"
  private val VersionRegex = """^CIRCT firtool-(\S+)$""".r

  private def validVersion(version: String): Boolean = version.matches("^[0-9A-Za-z.+_-]+$") &&
    version != "." && version != ".."

  private def resourceVersion(loader: ClassLoader): Option[String] = Try {
    val source = Source.fromURL(loader.getResource(s"$groupId/$artId/project.version"))
    try source.mkString finally source.close()
  }.toOption

  private def cacheDir: String = {
    val path = sys.env.getOrElse("CHISEL_FIRTOOL_CACHE", ProjectDirectories.from("", groupId, artId).cacheDir)
    new File(path).getAbsolutePath()
  }

  private def firtoolBin(version: String): Path = {
    val topDir = Paths.get(cacheDir).resolve(version)

    val destDir = topDir.resolve("bin")
    destDir.resolve("firtool")
  }

  private def installBinary(logger: Logger, destination: Path)(write: Path => Unit): Unit = {
    Files.createDirectories(destination.getParent)
    val tmp = Files.createTempFile(destination.getParent, destination.getFileName.toString, ".tmp")
    logger.debug(s"Created temporary file $tmp")
    try {
      write(tmp)
      // Use java.io.File APIs to support Windows.
      tmp.toFile.setWritable(true)
      tmp.toFile.setReadable(true)
      tmp.toFile.setExecutable(true)
      logger.debug(s"Atomically moving $tmp to $destination")
      Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE)
    } finally Files.deleteIfExists(tmp)
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
    val primaryPlatform = platform.toOption.get
    val resourceLoader = classloader.getOrElse(this.getClass.getClassLoader)

    val baseDir = s"$groupId/$artId"
    val versionFile = resourceLoader.getResource(s"$baseDir/project.version")
    val versionOpt = resourceVersion(resourceLoader)
    if (versionOpt.isEmpty) {
      val msg = s"firtool version not found in resources ($versionFile)"
      logger.debug(msg)
      return Left(msg)
    }

    val version = versionOpt.get
    if (!validVersion(version)) return Left(s"Invalid firtool version in resources: $version")
    logger.debug(s"Firtool version $version found in resources")

    // Try primary platform then fallback (e.g. macos-aarch64 → macos-x64) if no resource found
    val platformsToTry = Seq(primaryPlatform) ++ fallbackPlatform(primaryPlatform)
    val effectivePlatform = platformsToTry.find { p =>
      resourceLoader.getResource(s"$baseDir/$p/bin/firtool") != null
    }
    if (effectivePlatform.isEmpty) {
      val msg = s"firtool binary not found in resources for platforms: ${platformsToTry.mkString(", ")}"
      logger.debug(msg)
      return Left(msg)
    }
    if (effectivePlatform.get != primaryPlatform) {
      logger.debug(s"Falling back to ${effectivePlatform.get} resources (no $primaryPlatform resources found)")
    }
    val artDir = s"$baseDir/${effectivePlatform.get}"

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
        try installBinary(logger, destBin) { tmp =>
          Files.copy(resourceBin, tmp, StandardCopyOption.REPLACE_EXISTING)
        } finally resourceBin.close()
      }
      if (result.isFailure) {
        val msg = s"Copying firtool failed with ${result.failed.get}"
        logger.debug(msg)
        return Left(msg)
      }
    }
    Right(FirtoolBinary(destFile, version))
  }

  private def checkInstalled(logger: Logger, version: String): Either[String, FirtoolBinary] = {
    val destFile: File = firtoolBin(version).toFile
    if (destFile.isFile) {
      logger.debug(s"Firtool binary with version ($version) $destFile already exists")
      Right(FirtoolBinary(destFile, version))
    } else {
      val msg = s"Firtool binary with version ($version) does not already exist"
      logger.debug(msg)
      Left(msg)
    }
  }

  /** Run body while holding the per-version cache lock, both within this JVM and across processes. */
  private def withVersionLock[A](logger: Logger, version: String)(body: => A): A = this.synchronized {
    val cache = Paths.get(cacheDir)
    Files.createDirectories(cache)
    val lockPath = cache.resolve(s"$version.lock")
    logger.debug(s"Acquiring firtool cache lock $lockPath")
    val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      val lock = channel.lock()
      try body
      finally lock.release()
    } finally channel.close()
  }

  /** Find the release asset for the primary platform, or else for its fallback platform. */
  private def findRelease(logger: Logger, version: String, primary: String): Either[String, GitHub.Release] =
    GitHub.lookup(logger, version, primary) match {
      case Some(release) => Right(release)
      case None =>
        val fallback = fallbackPlatform(primary)
        fallback.flatMap(GitHub.lookup(logger, version, _)) match {
          case Some(release) =>
            logger.warn(s"Falling back to platform ${release.platform} (no $primary release asset for firtool $version)")
            Right(release)
          case None =>
            val platforms = (primary +: fallback.toSeq).mkString(" or ")
            Left(s"No GitHub release asset for firtool $version on $platforms")
        }
    }

  private def warnIfVersionDiffers(logger: Logger, binary: Path, version: String): Unit = {
    val output = mutable.ArrayBuffer.empty[String]
    val command = Seq(binary.toString, "--version")
    logger.debug(s"Running: ${command.mkString(" ")}")
    Try(command.!(ProcessLogger(output += _))) match {
      case Success(0) =>
        output.collectFirst { case VersionRegex(v) => v }.filter(_ != version).foreach { actual =>
          logger.warn(s"Downloaded firtool version $actual differs from requested version $version")
        }
      case result => logger.warn(s"Unable to check downloaded firtool version: $result ${output.mkString("\n")}")
    }
  }

  private def fetchFromGitHub(logger: Logger, version: String): Either[String, FirtoolBinary] = {
    logger.debug(s"Attempting to fetch firtool $version from GitHub releases")
    val result = Try {
      for {
        primary <- determinePlatform(logger)
        binary <- withVersionLock(logger, version) {
          // Another process may have installed this version while we waited for the lock.
          checkInstalled(logger, version).left.flatMap { _ =>
            findRelease(logger, version, primary).map { release =>
              val destination = firtoolBin(version)
              installBinary(logger, destination)(GitHub.extract(logger, release, _))
              warnIfVersionDiffers(logger, destination, version)
              FirtoolBinary(destination.toFile, version)
            }
          }
        }
      } yield binary
    }
    result.toEither.left.map(_.toString).flatMap(identity).left.map { error =>
      val msg = s"Fetching firtool $version from GitHub failed: $error"
      logger.debug(msg)
      msg
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

    // Try fetching for a specific platform classifier; returns Left on any failure.
    def tryFetch(p: String): Either[String, FirtoolBinary] = {
      // See coursier.parse.DependencyParser to understand how the classifier is added via Publication
      val org = Organization(groupId)
      val module = Module(org, ModuleName(s"$artId"), Map())
      val dep =
        Dependency(module, defaultVersion)
          .withPublication("", Type.empty, Extension.empty, Classifier(p))
      // One would think there'd be a built-in pretty print like this but there isn't
      //   (coursier.util.Print doesn't include the classifier)
      logger.debug(s"Attempting to fetch ${dep.module}:${dep.version},classifier=${p}")

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
      // A version override must not be shadowed by resources in the parent classloader.
      val classloader = new URLClassLoader(Array(jar.toURI.toURL), null)
      try checkResources(Some(classloader), logger)
      finally classloader.close()
    }

    tryFetch(platform) match {
      case right @ Right(_) => right
      case left @ Left(_) =>
        fallbackPlatform(platform) match {
          case Some(fallback) =>
            logger.debug(s"Falling back to platform $fallback")
            tryFetch(fallback)
          case None => left
        }
    }
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
    checkFirtoolPath(logger).flatMap {
      case Right(binary) => Right(binary)
      case Left(pathError) =>
        val overrideVersion = sys.props.get("chisel.firtool.version").orElse(sys.env.get("CHISEL_FIRTOOL_VERSION"))
        val version = overrideVersion.getOrElse(defaultVersion)
        val resources = overrideVersion match {
          case Some(v) =>
            logger.debug(s"Using explicit firtool version override: $v")
            resourceVersion(this.getClass.getClassLoader).foreach { classpathVersion =>
              logger.warn(s"Firtool version override $v takes precedence over llvm-firtool $classpathVersion on the classpath")
            }
            Left("Classpath lookup skipped because an explicit firtool version is set")
          case None => checkResources(None, logger).map { binary =>
            logger.info(s"using llvm-firtool ${binary.version} from classpath; this mechanism is deprecated, set chisel.firtool.version instead")
            binary
          }
        }
        resources match {
          case Right(binary) => Right(binary)
          case Left(resourceError) if !validVersion(version) =>
            Left(List("Failed to fetch firtool:", pathError, resourceError, s"Invalid firtool version: $version").mkString("\n"))
          case Left(resourceError) =>
            // Left projections short-circuit on success and aggregate all failed steps.
            for {
              cacheError <- checkInstalled(logger, version).left
              githubError <- fetchFromGitHub(logger, version).left
              mavenError <- fetchArtifact(logger, version).left
            } yield List("Failed to fetch firtool:", pathError, resourceError, cacheError, githubError, mavenError).mkString("\n")
        }
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
