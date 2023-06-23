import mill._, scalalib._, publish._

object llvmFirtoolNative extends Cross[LLVMFirtoolNativeModule]("macos-x86", "linux-x86")
//("windows-x86", "macos-x86", "linux-x86")

trait LLVMFirtoolNativeModule extends Cross.Module[String] with JavaModule with PublishModule {
  def suffix = T { "_" + crossValue }

  def publishVersion = "1.44.0-SNAPSHOT"

  // TODO parameterize
  private val firtoolVersion = "1.44.0"

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

  private val lookup = Map(
    "macos-x86" -> "macos-11",
    "linux-x86" -> "ubuntu-20.04"
  )

  private val tarballName = s"firrtl-bin-${lookup(crossValue)}.tar.gz"

  private val url = s"https://github.com/llvm/circt/releases/download/firtool-$firtoolVersion/$tarballName"

  def tarball = T {
    val file = T.dest / tarballName
    os.write(file, requests.get.stream(url))
    PathRef(file)
  }

  def extracted = T {
    os.proc("tar", "zxf", tarball().path)
      .call(cwd = T.dest)
    // Rename the directory to a standard path
    val downloadedDir = os.list(T.dest).head
    val destDir = T.dest / "firtool"
    os.move(downloadedDir, destDir)
    // Record the version in a file
    os.write(destDir / "version", firtoolVersion)
    PathRef(T.dest)
  }

  def resources = T { Seq(extracted()) }

  //def bigSuffix = T { "[[[" + suffix() + "]]]" }
  //def sources = T.sources(millSourcePath)
}

object firtoolResolver extends ScalaModule {
  def scalaVersion = "2.13.11"
  def ivyDeps = Agg(
    ivy"dev.dirs:directories:26",
    ivy"com.lihaoyi::os-lib:0.9.1",
    ivy"com.outr::scribe:3.11.5",
    ivy"io.get-coursier::coursier:2.1.5",
    // For testing
    ivy"org.chipsalliance:llvmFirtoolNative-macos-x86:1.44.0-SNAPSHOT"
  )
  //object test extends ScalaTests {
  //  def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
  //  def testFramework = "utest.runner.Framework"
  //}
}
