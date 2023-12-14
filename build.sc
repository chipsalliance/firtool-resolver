// SPDX-License-Identifier: Apache-2.0

import mill._
import scalalib._
import publish._
import mill.util.Jvm

import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import de.tobiasroeser.mill.vcs.version.VcsVersion

case class Platform(os: String, arch: String) {
  override def toString: String = s"$os-$arch"
}
object Platform {
  // Needed to use Platform's in T[_] results
  import upickle.default.{ReadWriter, macroRW}
  implicit val rw: ReadWriter[Platform] = macroRW
}

trait ChipsAlliancePublishModule extends PublishModule {

  def isSnapshot: Boolean

  override def sonatypeUri: String = "https://s01.oss.sonatype.org/service/local"
  override def sonatypeSnapshotUri: String = "https://s01.oss.sonatype.org/content/repositories/snapshots"
  protected def getEnvVariable(name: String): String =
    sys.env.get(name).getOrElse(s"Environment variable $name must be defined!")

  def gpgArgs: Seq[String] = Seq(
    "--detach-sign",
    "--batch=true",
    "--yes",
    "--passphrase",
    getEnvVariable("PGP_PASSPHRASE"),
    "--armor",
    "--use-agent"
  )

  // Helper for publishing, sets values so we don't have to set them on the command-line
  def publishSigned() = T.command {
    val release = !isSnapshot
    publish(gpgArgs = gpgArgs, release = release)
  }
}

// Must run mill with -i because it uses environment variables:
// LLVM_FIRTOOL_VERSION - (eg. 1.58.0)
// LLVM_FIRTOOL_PRERELEASE - 0 means real release (non-SNAPSHOT), otherwise is -SNAPSHOT
object `llvm-firtool` extends JavaModule with ChipsAlliancePublishModule {

  def firtoolVersion = getEnvVariable("LLVM_FIRTOOL_VERSION")
  // FNDDS requires that the publish version start with firtool version with optional -<suffix>
  def isSnapshot = getEnvVariable("LLVM_FIRTOOL_PRERELEASE") != "0"

  def publishSuffix = if (isSnapshot) "-SNAPSHOT" else ""
  def publishVersion = firtoolVersion + publishSuffix

  private def FNDDSSpecVersion = "1.0.0"
  private def groupId = "org.chipsalliance"
  // artifactId is the the name of this object
  private def artId = "llvm-firtool"
  private def binName = "firtool"
  private def releaseUrl = s"https://github.com/llvm/circt/releases/download/firtool-${firtoolVersion}"

  val platforms = Seq[Platform](
    Platform("macos", "x64"),
    Platform("linux", "x64"),
    Platform("windows", "x64")
  )

  def pomSettings = PomSettings(
    description = "Package of native firtool binary",
    organization = "org.chipsalliance",
    url = "https://chipsalliance.org",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "llvm-firtool"),
    developers = Seq(
      Developer("jackkoenig", "Jack Koenig", "https://github.com/jackkoenig")
    )
  )

  private def getBaseDir(dir: os.Path): os.Path = dir / groupId / artId

  // Downloaded tarball for each platform
  def tarballs = T {
    platforms.map { platform =>
      val tarballName = if (platform.os == "windows") {
        s"firrtl-bin-$platform.zip"
      } else {
        s"firrtl-bin-$platform.tar.gz"
      }
      val archiveUrl = s"$releaseUrl/$tarballName"
      val file = T.dest / tarballName
      os.write(file, requests.get.stream(archiveUrl))
      platform -> PathRef(file)
    }
  }

  def extractedDirs = T {
    val tarballLookup = tarballs().toMap
    platforms.map { platform =>
      val tarball = tarballLookup(platform)
      val dir = T.dest / platform.toString
      os.makeDir.all(dir)
      // Windows uses .zip
      if (platform.os == "windows") {
        os.proc("unzip", tarball.path)
          .call(cwd = dir)
      } else {
        os.proc("tar", "zxf", tarball.path)
          .call(cwd = dir)
      }
      val downloadedDir = os.list(dir).head
      val baseDir = getBaseDir(dir)
      os.makeDir.all(baseDir)

      // Rename the directory to the FNNDS specificed path
      val artDir = baseDir / platform.toString
      os.move(downloadedDir, artDir)

      // If on windows, rename firtool.exe to firtool
      if (platform.os == "windows") {
        os.walk(artDir).foreach { path =>
          if (path.baseName == "firtool" && path.ext == "exe") {
            // OS lib doesn't seem to have a way to get the directory
            val parent = os.Path(path.toIO.getParentFile)
            os.move(path, parent / path.baseName)
          }
        }
      }

      platform -> PathRef(dir)
    }
  }

  // Directories that will be turned into platform-specific classifier jars
  def classifierDirs = T {
    extractedDirs().map { case (platform, dir) =>
      os.copy.into(dir.path, T.dest)
      // We added a platform directory above in extractedDirs, remove it to get actual root
      val rootDir = T.dest / platform.toString

      platform -> PathRef(rootDir)
    }
  }

  // Classifier jars will be included as extras so that platform-specific jars can be fetched
  def classifierJars = T {
    classifierDirs().map { case (platform, dir) =>
      val jarPath = T.dest / s"$platform.jar"
      Jvm.createJar(
        jarPath,
        Agg(dir.path, fnddsMetadata().path),
        mill.api.JarManifest.MillDefault,
        (_, _) => true
      )
      platform -> PathRef(jarPath)
    }
  }

  def extraPublish = T {
    classifierJars().map { case (platform, jar) =>
      PublishInfo(
        jar,
        classifier = Some(platform.toString),
        ext = "jar",
        ivyConfig = "compile" // TODO is this right?
      )
    }
  }

  def fnddsMetadata = T {
    // Then get the baseDir from there
    val baseDir = getBaseDir(T.dest)
    os.makeDir.all(baseDir)
    os.write(baseDir / "FNDDS.version", FNDDSSpecVersion)
    os.write(baseDir / "project.version", publishVersion())
    PathRef(T.dest)
  }

  def localClasspath = T {
    super.localClasspath() ++ extractedDirs().map { case (_, dir) => dir } ++ Seq(fnddsMetadata())
  }
}

object `firtool-resolver` extends ScalaModule with ChipsAlliancePublishModule {
  def scalaVersion = "2.13.11"

  def publishVersion = VcsVersion.vcsState().format()

  def isSnapshot = true

  def pomSettings = PomSettings(
    description = "Fetcher for native firtool binary",
    organization = "org.chipsalliance",
    url = "https://chipsalliance.org",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "llvm-firtool"),
    developers = Seq(
      Developer("jackkoenig", "Jack Koenig", "https://github.com/jackkoenig")
    )
  )

  def ivyDeps = Agg(
    ivy"dev.dirs:directories:26",
    ivy"com.lihaoyi::os-lib:0.9.1",
    ivy"com.outr::scribe:3.11.5",
    ivy"io.get-coursier::coursier:2.1.5",
  )
}
