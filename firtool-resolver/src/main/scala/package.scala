// SPDX-License-Identifier: Apache-2.0

import scala.util.{Failure, Success, Try}
import scala.io.Source
import java.io.File
import java.nio.file.{Path, Paths, Files}
import java.net.URLClassLoader

import dev.dirs.{BaseDirectories, ProjectDirectories, UserDirectories}

package object firtoolresolver {
  // Important constants
  private[firtoolresolver] def groupId = "org.chipsalliance"
  private[firtoolresolver] def artId = "llvm-firtool"
  private[firtoolresolver] def cacheDir: String = {
    val path = sys.env.getOrElse("CHISEL_FIRTOOL_CACHE", ProjectDirectories.from("", groupId, artId).cacheDir)
    new File(path).getAbsolutePath()
  }

  private[firtoolresolver] lazy val operatingSystem: Either[String, String] = {
    // Mac OS X
    // Linux
    val osName = System.getProperty("os.name")
    val name = osName.toLowerCase
    if (name.startsWith("win")) Right("windows")
    else if (name.startsWith("mac")) Right("macos")
    else if (name.startsWith("linux")) Right("linux")
    else Left(s"Unsupported OS: $osName")
  }

  private[firtoolresolver] lazy val architecture: Either[String, String] = {
    // amd64, x86_64
    // aarch64
    val osArch = System.getProperty("os.arch")
    val arch = osArch.toLowerCase
    if (arch == "amd64" || arch == "x86_64") Right("x64")
    else if (arch == "aarch64") Right("aarch64")
    else Left(s"Unsupported architecture: $osArch")
  }

  // Use x64 for Apple Silicon
  private[firtoolresolver] def appleSiliconFixup(logger: Logger, os: String, arch: String): String = {
    if (os == "macos" && arch == "aarch64") {
      logger.debug("Using x64 architecture for Apple silicon")
      "x64"
    } else {
      arch
    }
  }
  def determinePlatform(logger: Logger): Either[String, String] =
    for {
      os <- operatingSystem
      _arch <- architecture
      arch = appleSiliconFixup(logger, os, _arch)
    } yield s"$os-$arch"

  private[firtoolresolver] def checkResources(classloader: Option[URLClassLoader], logger: Logger): Either[String, FirtoolBinary] = {
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
    // Copying a file is not thread-safe
    this.synchronized {
      if (destFile.isFile()) {
        logger.debug(s"Firtool binary $destFile already exists")
      } else {
        // Copy
        logger.debug(s"Copying firtool from resources to $destFile")
        val resourceBin = resourceLoader.getResourceAsStream(s"$artDir/bin/firtool")
        val result = Try {
          Files.createDirectories(destBin.getParent)
          Files.copy(resourceBin, destBin)
          // os-lib only supports posix permissions, use java.io to support Windows
          destFile.setWritable(true)
          destFile.setReadable(true)
          destFile.setExecutable(true)
        }
        if (result.isFailure) {
          val msg = s"Copying firtool failed with ${result.failed.get}"
          logger.debug(msg)
          return Left(msg)
        }
      }
    }
    Right(FirtoolBinary(destFile, version))
  }

  private[firtoolresolver] def firtoolBin(version: String): Path = {
    val topDir = Paths.get(cacheDir).resolve(version)

    val destDir = topDir.resolve("bin")
    destDir.resolve("firtool")
  }
}
