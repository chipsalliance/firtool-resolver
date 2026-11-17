// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import TestFixtures._

/** Facts about the machine running the tests. */
private[firtoolresolver] object Host {
  private val osName = System.getProperty("os.name").toLowerCase
  val windows: Boolean = osName.startsWith("win")
  val mac: Boolean = osName.startsWith("mac")
  val aarch64: Boolean = System.getProperty("os.arch") == "aarch64"
  val platform: String =
    if (windows) "windows-x64" else if (mac) { if (aarch64) "macos-aarch64" else "macos-x64" } else "linux-x64"

  /** JVM options that make a child process believe it runs on another platform. */
  val macArm64: Seq[String] = Seq("-Dos.name=Mac OS X", "-Dos.arch=aarch64")
  val windowsX64: Seq[String] = Seq("-Dos.name=Windows 11", "-Dos.arch=amd64")
}

/** A published release asset and its checksum file. */
private[firtoolresolver] final case class Asset(archive: Path, checksum: Path)

/** Captured result of a finished child JVM; stdout holds the resolved path, stderr holds the log. */
private[firtoolresolver] final case class Result(exit: Int, out: String, err: String) {
  def output: String = s"exit code: $exit\n--- stdout ---\n$out\n--- stderr ---\n$err"
}

private[firtoolresolver] final class Child(process: Process, outFile: Path, errFile: Path) {
  private def read(path: Path): String = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)

  def await(timeoutSeconds: Long = 300): Result = {
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw new AssertionError(s"Timed out after ${timeoutSeconds}s\n--- stderr ---\n${read(errFile)}")
    }
    Result(process.exitValue(), read(outFile), read(errFile))
  }
}

/** A hermetic world for one test: a fake GitHub release mirror, a fake Maven repository, a firtool cache, and
  * the ability to launch the resolver's `Main` in a fresh JVM against all of them.
  *
  * @param classpathProperty system property holding the classpath to launch the resolver with
  * @param platforms the platforms for which a release asset is published in the mirror
  */
private[firtoolresolver] class Sandbox(
  val root: Path,
  classpathProperty: String = "firtoolresolver.test.classpath",
  platforms: Seq[String] = Seq(Host.platform)
) {
  val version = "9.9.9-hermetic"
  val classpathVersion = "1.0.0-classpath"
  val base: Path = root.resolve("mirror")
  val cache: Path = root.resolve("cache")
  val maven: Path = root.resolve("maven")

  def script(output: String, exitCode: Int = 0): Array[Byte] = bytes(s"#!/bin/sh\necho $output\nexit $exitCode\n")
  val executable: Array[Byte] = script(s"CIRCT firtool-$version")

  def circtPlatform(platform: String): String = if (platform == "macos-aarch64") "macos-arm64" else platform

  /** Publish the release asset for one platform, as CIRCT's GitHub releases lay them out. */
  def publishRelease(platform: String, forVersion: String = version): Asset = {
    val windowsAsset = platform.startsWith("windows-")
    val name = s"firrtl-bin-${circtPlatform(platform)}.${if (windowsAsset) "zip" else "tar.gz"}"
    val release = base.resolve(s"firtool-$forVersion")
    Files.createDirectories(release)
    val archive = release.resolve(name)
    // Windows tests only need a payload to verify extraction; version probing is advisory.
    if (windowsAsset) zip(archive, Seq(Entry("release/bin/firtool.exe", executable)))
    else tar(archive, Seq(Entry("release/bin/firtool", executable)))
    val checksum = release.resolve(name + ".sha256")
    write(checksum, bytes(s"${digest(archive)}  $name\n"))
    Asset(archive, checksum)
  }

  val assets: Seq[Asset] = platforms.map(publishRelease(_))
  /** The asset of the first platform, for tests which tamper with it. */
  def asset: Asset = assets.head

  /** Publish the legacy llvm-firtool jars (one per platform classifier) into the fake Maven repository. */
  def publishMaven(forVersion: String = version, forPlatforms: Seq[String] = Seq(Host.platform)): Unit = {
    val dir = maven.resolve(s"org/chipsalliance/llvm-firtool/$forVersion")
    write(dir.resolve(s"llvm-firtool-$forVersion.pom"), bytes(
      s"<project><modelVersion>4.0.0</modelVersion><groupId>org.chipsalliance</groupId>" +
        s"<artifactId>llvm-firtool</artifactId><version>$forVersion</version></project>"))
    forPlatforms.foreach { platform =>
      zip(dir.resolve(s"llvm-firtool-$forVersion-$platform.jar"), Seq(
        Entry("org.chipsalliance/llvm-firtool/project.version", bytes(forVersion)),
        Entry(s"org.chipsalliance/llvm-firtool/$platform/bin/firtool", executable)))
    }
  }

  /** Resources as llvm-firtool puts them on the classpath, as a directory to add to the classpath. */
  def classpathResources(forVersion: String = classpathVersion): Path = {
    val resources = root.resolve("resources")
    val dir = "org.chipsalliance/llvm-firtool"
    write(resources.resolve(s"$dir/project.version"), bytes(forVersion))
    write(resources.resolve(s"$dir/${Host.platform}/bin/firtool"), executable)
    resources
  }

  /** Put an already-installed firtool into the cache. */
  def preinstall(forVersion: String = version, content: Array[Byte] = executable): Path = {
    val binary = cache.resolve(s"$forVersion/bin/firtool")
    write(binary, content)
    binary.toFile.setExecutable(true)
    binary
  }

  def binary(forVersion: String = version): Path = cache.resolve(s"$forVersion/bin/firtool")

  /** Launch `firtoolresolver.Main` in a new JVM.
    *
    * Every optional environment variable is removed unless explicitly provided, so tests only see what they ask for.
    */
  def launch(
    arg: String = "0.0.0",
    verbose: Boolean = true,
    envVersion: Option[String] = Some(version),
    envURL: Option[String] = Some(base.toUri.toString),
    envCache: Option[String] = Some(cache.toString),
    envPath: Option[String] = None,
    envRepos: Option[String] = Some(maven.toUri.toString),
    javaOpts: Seq[String] = Nil,
    extraClasspath: Seq[Path] = Nil
  ): Child = {
    val classpath = System.getProperty(classpathProperty)
    require(classpath != null, s"System property $classpathProperty is not set")
    val cp = (extraClasspath.map(_.toString) :+ classpath).mkString(java.io.File.pathSeparator)
    val javaCommand = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command =
      Seq(javaCommand) ++ javaOpts ++ Seq("-cp", cp, "firtoolresolver.Main") ++ (if (verbose) Seq("-v") else Nil) :+ arg
    val out = Files.createTempFile(root, "stdout-", ".txt")
    val err = Files.createTempFile(root, "stderr-", ".txt")
    val builder = new ProcessBuilder(command: _*)
      .redirectOutput(out.toFile)
      .redirectError(err.toFile)
    val env = builder.environment()
    Seq("CHISEL_FIRTOOL_PATH", "CHISEL_FIRTOOL_VERSION", "CHISEL_FIRTOOL_CACHE", "CHISEL_FIRTOOL_URL",
      "COURSIER_REPOSITORIES", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").foreach(env.remove(_))
    envPath.foreach(env.put("CHISEL_FIRTOOL_PATH", _))
    envVersion.foreach(env.put("CHISEL_FIRTOOL_VERSION", _))
    envCache.foreach(env.put("CHISEL_FIRTOOL_CACHE", _))
    envURL.foreach(env.put("CHISEL_FIRTOOL_URL", _))
    envRepos.foreach(env.put("COURSIER_REPOSITORIES", _))
    env.put("COURSIER_CACHE", root.resolve("coursier").toString)
    new Child(builder.start(), out, err)
  }

  /** Run an installed firtool, as the shell tests did with `$FIRTOOL --version`. */
  def runFirtool(path: Path): String = {
    val process = new ProcessBuilder(path.toString, "--version").redirectErrorStream(true).start()
    val source = scala.io.Source.fromInputStream(process.getInputStream)
    val output = try source.mkString finally source.close()
    process.waitFor()
    output.trim
  }
}
