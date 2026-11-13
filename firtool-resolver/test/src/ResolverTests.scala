// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import TestFixtures._

/** Hermetic resolver tests run real JVMs against local release and Maven fixtures. */
class ResolverTests extends TemporaryDirectorySuite {
  private val version = "9.9.9-hermetic"
  private val windows = System.getProperty("os.name").toLowerCase.startsWith("win")
  private val platform = if (windows) "windows-x64" else if (System.getProperty("os.name").toLowerCase.startsWith("mac")) {
    if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x64"
  } else "linux-x64"
  private val circtPlatform = if (platform == "macos-aarch64") "macos-arm64" else platform
  private val executable = bytes(s"#!/bin/sh\necho CIRCT firtool-$version\n")

  private class Mirror(val root: Path) {
    val base = root.resolve("mirror")
    private val asset = s"firrtl-bin-$circtPlatform.${if (windows) "zip" else "tar.gz"}"
    private val release = base.resolve(s"firtool-$version")
    Files.createDirectories(release)
    val archive = release.resolve(asset)
    val checksum = release.resolve(asset + ".sha256")
    val cache = root.resolve("cache")
    // Windows tests only need a payload to verify extraction; version probing is advisory.
    if (windows) zip(archive, Seq(Entry("release/bin/firtool.exe", executable)))
    else tar(archive, Seq(Entry("release/bin/firtool", executable)))
    write(checksum, bytes(s"${digest(archive)}  $asset\n"))

    def launch(extraClasspath: Option[Path] = None, envVersion: String = version,
               envURL: String = base.toUri.toString): Process = {
      val classpath = System.getProperty("firtoolresolver.test.classpath")
      val cp = extraClasspath.map(p => s"$p${java.io.File.pathSeparator}$classpath").getOrElse(classpath)
      val javaCommand = Paths.get(System.getProperty("java.home"), "bin", "java").toString
      val command = Seq(javaCommand, "-cp", cp, "firtoolresolver.Main", "-v", "0.0.0")
      val builder = new ProcessBuilder(command: _*).redirectErrorStream(true)
      val env = builder.environment()
      env.remove("CHISEL_FIRTOOL_PATH")
      env.put("CHISEL_FIRTOOL_VERSION", envVersion)
      env.put("CHISEL_FIRTOOL_CACHE", cache.toString)
      env.put("CHISEL_FIRTOOL_URL", envURL)
      env.put("COURSIER_CACHE", root.resolve("coursier").toString)
      env.put("COURSIER_REPOSITORIES", root.resolve("maven").toUri.toString)
      builder.start()
    }

    def classpathResources(): Path = {
      val resources = root.resolve("resources")
      write(resources.resolve("org.chipsalliance/llvm-firtool/project.version"), bytes("1.0.0-classpath"))
      resources
    }
  }

  private def finish(process: Process, expectedExitCode: Int = 0): String = {
    val source = scala.io.Source.fromInputStream(process.getInputStream)
    val output = try source.mkString finally source.close()
    assertEquals(process.waitFor(), expectedExitCode, output)
    output
  }

  directory.test("concurrent JVMs download once and reuse the locked cache") { root =>
    val mirror = new Mirror(root)
    val processes = (1 to 4).map(_ => mirror.launch())
    val outputs = processes.map(finish(_))
    assertEquals(outputs.count(_.contains(s"Downloading firtool $version for")), 1, outputs.mkString("\n"))
    val binary = mirror.cache.resolve(s"$version/bin/firtool")
    assertEquals(Files.readAllBytes(binary).toSeq, executable.toSeq)
    // The payload is a shell script, so it can only be run (and version-checked) off Windows.
    if (!windows) {
      assert(Files.isExecutable(binary), binary)
      outputs.foreach(output => assert(!output.contains("Unable to check downloaded firtool version"), output))
    }
    val paths = Files.walk(mirror.cache)
    try assert(!paths.iterator().asScala.exists { path =>
      path.getFileName.toString.endsWith(".tmp") || path.getFileName.toString.startsWith("firtool-archive-")
    }) finally paths.close()
  }

  directory.test("invalid version overrides fail before fetching or creating the cache") { root =>
    val mirror = new Mirror(root)
    val output = finish(mirror.launch(envVersion = "../escape"), 1)
    assert(output.contains("Invalid firtool version: ../escape"), output)
    assert(!output.contains("Attempting to fetch"), output)
    assert(!Files.exists(mirror.cache))
  }

  directory.test("Maven fallback honors the override despite a different classpath version") { root =>
    val mirror = new Mirror(root)
    val mavenDir = root.resolve(s"maven/org/chipsalliance/llvm-firtool/$version")
    write(mavenDir.resolve(s"llvm-firtool-$version.pom"), bytes(
      s"<project><modelVersion>4.0.0</modelVersion><groupId>org.chipsalliance</groupId>" +
        s"<artifactId>llvm-firtool</artifactId><version>$version</version></project>"))
    zip(mavenDir.resolve(s"llvm-firtool-$version-$platform.jar"), Seq(
      Entry("org.chipsalliance/llvm-firtool/project.version", bytes(version)),
      Entry(s"org.chipsalliance/llvm-firtool/$platform/bin/firtool", executable)))
    val output = finish(mirror.launch(extraClasspath = Some(mirror.classpathResources()), envURL = "file:///nonexistent"))
    assert(output.contains(s"Firtool version $version found in resources"), output)
    assertEquals(Files.readAllBytes(mirror.cache.resolve(s"$version/bin/firtool")).toSeq, executable.toSeq)
    assert(!Files.exists(mirror.cache.resolve("1.0.0-classpath")))
  }

  directory.test("checksum mismatch reports both hashes, aggregates errors, and cleans up") { root =>
    val mirror = new Mirror(root)
    write(mirror.checksum, bytes("0" * 64))
    val output = finish(mirror.launch(), 1)
    assert(output.contains(s"expected ${"0" * 64}, actual ${digest(mirror.archive)}"), output)
    assert(output.contains("Attempting to fetch org.chipsalliance:llvm-firtool:"), output)
    assert(output.contains("Failed to fetch firtool:"), output)
    assert(!Files.exists(mirror.cache.resolve(s"$version/bin/firtool")))
    val remaining = Files.walk(mirror.cache.resolve(version))
    try assertEquals(remaining.iterator().asScala.count(Files.isRegularFile(_)), 0) finally remaining.close()
  }
}
