// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import scala.util.{Failure, Success, Try}
import scala.collection.mutable
import scala.io.Source
import java.io.File
import java.nio.file.{Paths, Files}
import scala.sys.process._

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
  private def binaryName = "firtool"
  private val VersionRegex = """^CIRCT firtool-(\S+)$""".r

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
      msg4 <- FetchArtifact(logger, defaultVersion).left
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
