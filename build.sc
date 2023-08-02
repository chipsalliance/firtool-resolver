// SPDX-License-Identifier: Apache-2.0

import mill._, scalalib._, publish._

val platforms = Seq[String](
  "macos-x64",
  "linux-x64",
  "windows-x64"
)

object `llvm-firtool` extends Cross[LLVMFirtoolNativeModule](platforms)

trait LLVMFirtoolNativeModule extends Cross.Module[String] with JavaModule with PublishModule {

  private def platform = crossValue

  def suffix = T { s"-$platform" }

  def publishVersion = "1.48.0-SNAPSHOT"

  private def firtoolVersion = "1.48.0"

  private def groupId = "org.chipsalliance"

  // Note "artifactId" is defined by Mill itself
  private def artId = "llvm-firtool"

  private def MNDDSSpecVersion = "0.1.0"

  private def binName = "firtool"

  private val (operatingSystem, architecture) = platform.split("-") match {
    case Array(o, a) => (o, a)
  }

  def pomSettings = PomSettings(
    description = "Package of native firtool binary",
    organization = groupId,
    url = "https://chipsalliance.org",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "llvm-firtool"),
    developers = Seq(
      Developer("jackkoenig", "Jack Koenig", "https://github.com/jackkoenig")
    )
  )

  private val tarballName = if (operatingSystem == "windows") {
    s"firrtl-bin-$platform.zip"
  } else {
    s"firrtl-bin-$platform.tar.gz"
  }
  private val releaseUrl = "https://github.com/llvm/circt/releases/download/firtool-1.48.0"
  private val archiveUrl = s"$releaseUrl/$tarballName"

  def tarball = T {
    val file = T.dest / tarballName
    os.write(file, requests.get.stream(archiveUrl))
    PathRef(file)
  }

  def extracted = T {
    // Windows uses .zip
    if (operatingSystem == "windows") {
      os.proc("unzip", tarball().path)
        .call(cwd = T.dest)
    } else {
      os.proc("tar", "zxf", tarball().path)
        .call(cwd = T.dest)
    }
    val downloadedDir = os.list(T.dest).head
    val baseDir = T.dest / groupId / artId
    os.makeDir.all(baseDir)
    // Record MNDDS version
    os.write(baseDir / "MNDDS.version", MNDDSSpecVersion)

    // Rename the directory to the MNEDS 0.1.0 specificed path
    val artDir = baseDir / platform
    os.move(downloadedDir, artDir)
    // Record the version in a file
    os.write(artDir / "version", firtoolVersion)
    PathRef(T.dest)
  }

  def resources = T { Seq(extracted()) }
}

object `firtool-resolver` extends ScalaModule {
  def scalaVersion = "2.13.11"
  def ivyDeps = Agg(
    ivy"dev.dirs:directories:26",
    ivy"com.lihaoyi::os-lib:0.9.1",
    ivy"com.outr::scribe:3.11.5",
    ivy"io.get-coursier::coursier:2.1.5",
    // For testing
    //ivy"org.chipsalliance:llvm-firtool-macos-x64:1.48.0-SNAPSHOT"
  )
  //object test extends ScalaTests {
  //  def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
  //  def testFramework = "utest.runner.Framework"
  //}
}
