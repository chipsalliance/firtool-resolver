// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import Check._
import TestFixtures._

/** Hermetic resolver tests run real JVMs against local release and Maven fixtures.
  *
  * This covers what used to be the FileCheck scripts; the only things left out are those requiring real servers
  * (see [[LiveTests]]).
  *
  * @param classpathProperty the classpath the resolver is run with
  */
abstract class ResolverSuite(classpathProperty: String) extends TemporaryDirectorySuite with LogAssertions {
  import Host.windows

  private def sandbox(root: Path, platforms: Seq[String] = Seq(Host.platform)) =
    new Sandbox(root, classpathProperty, platforms)

  private def succeeded(child: Child): Result = {
    val result = child.await()
    assertEquals(result.exit, 0, result.output)
    result
  }

  private def failed(child: Child): Result = {
    val result = child.await()
    assertEquals(result.exit, 1, result.output)
    result
  }

  private def walk(path: Path): Vector[Path] = {
    val paths = Files.walk(path)
    try paths.iterator().asScala.toVector finally paths.close()
  }

  // ---- Downloading from the GitHub release mirror (github.sh) ----

  directory.test("concurrent JVMs download once and reuse the locked cache") { root =>
    val s = sandbox(root)
    val processes = (1 to 4).map(_ => s.launch())
    val results = processes.map(succeeded)
    val errors = results.map(_.err)
    assertEquals(errors.count(_.contains(s"Downloading firtool ${s.version} for")), 1, errors.mkString("\n"))
    assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
    // The payload is a shell script, so it can only be run (and version-checked) off Windows.
    if (!windows) {
      assert(Files.isExecutable(s.binary()), s.binary())
      errors.foreach(error => assertAbsent(error, "Unable to check downloaded firtool version"))
    }
    assert(!walk(s.cache).exists { path =>
      path.getFileName.toString.endsWith(".tmp") || path.getFileName.toString.startsWith("firtool-archive-")
    })
  }

  directory.test("downloads from the GitHub release mirror and does not fetch from Maven") { root =>
    val s = sandbox(root)
    val result = succeeded(s.launch(arg = s.version, envVersion = None))
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      has("chisel.firtool.path and CHISEL_FIRTOOL_PATH not set"),
      has("Checking resources for firtool"),
      has("firtool version not found in resources"),
      has(s"Firtool binary with version (${s.version}) does not already exist"),
      has(s"Attempting to fetch firtool ${s.version} from GitHub releases"),
      has("Acquiring firtool cache lock"),
      has("Downloading SHA-256 checksum"),
      has(s"Downloading firtool ${s.version} for"),
      has("Extracting firtool from"),
      has("Verified SHA-256 checksum:"),
      has("Atomically moving"))
    assertAbsent(result.err, "Attempting to fetch org.chipsalliance:llvm-firtool:")
    assertEquals(java.nio.file.Paths.get(result.out.trim), s.binary())
    if (!windows) assertEquals(s.runFirtool(s.binary()), s"CIRCT firtool-${s.version}")
  }

  directory.test("extracts the Windows zip layout") { root =>
    val s = sandbox(root, Seq("windows-x64"))
    val result = succeeded(s.launch(arg = s.version, envVersion = None, javaOpts = Host.windowsX64))
    checkLog(result.err,
      has("firrtl-bin-windows-x64.zip.sha256"),
      has(s"Downloading firtool ${s.version} for windows-x64 from"),
      has("Verified SHA-256 checksum:"))
    assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
  }

  directory.test("invalid version overrides fail before fetching or creating the cache") { root =>
    val s = sandbox(root)
    val result = failed(s.launch(envVersion = Some("../escape")))
    assert(result.err.contains("Invalid firtool version: ../escape"), result.output)
    assertAbsent(result.err, "Attempting to fetch")
    assert(!Files.exists(s.cache))
  }

  directory.test("checksum mismatch reports both hashes, aggregates errors, and cleans up") { root =>
    val s = sandbox(root)
    write(s.asset.checksum, bytes("0" * 64))
    val result = failed(s.launch())
    assert(result.err.contains(s"expected ${"0" * 64}, actual ${digest(s.asset.archive)}"), result.output)
    assert(result.err.contains("Attempting to fetch org.chipsalliance:llvm-firtool:"), result.output)
    assert(result.err.contains("Failed to fetch firtool:"), result.output)
    assert(!Files.exists(s.binary()))
    assertEquals(walk(s.cache.resolve(s.version)).count(Files.isRegularFile(_)), 0)
  }

  // ---- Already installed (installed.sh) ----

  directory.test("reuses a firtool that is already in the cache") { root =>
    val s = sandbox(root)
    val installed = s.preinstall()
    val result = succeeded(s.launch(arg = s.version, envVersion = None, envURL = Some("file:///nonexistent")))
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      has("chisel.firtool.path and CHISEL_FIRTOOL_PATH not set"),
      has("Checking resources for firtool"),
      has("firtool version not found in resources"),
      rx(s"Firtool binary with version \\(${java.util.regex.Pattern.quote(s.version)}\\) .*firtool already exists"))
    assertAbsent(result.err, "Attempting to fetch")
    assertEquals(java.nio.file.Paths.get(result.out.trim), installed)
    if (!windows) assertEquals(s.runFirtool(installed), s"CIRCT firtool-${s.version}")
  }

  // ---- llvm-firtool on the classpath (on_classpath.sh, firtool_cache.sh) ----

  directory.test("copies firtool from classpath resources into the cache") { root =>
    val s = sandbox(root)
    val result = succeeded(s.launch(
      envVersion = None, envURL = Some("file:///nonexistent"), extraClasspath = Seq(s.classpathResources())))
    val destination = s.binary(s.classpathVersion)
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      has("chisel.firtool.path and CHISEL_FIRTOOL_PATH not set"),
      has("Checking resources for firtool"),
      has(s"Firtool version ${s.classpathVersion} found in resources"),
      has(s"Copying firtool from resources to $destination"),
      has(s"using llvm-firtool ${s.classpathVersion} from classpath; this mechanism is deprecated, " +
        "set chisel.firtool.version instead"))
    assertAbsent(result.err, "Attempting to fetch")
    assertEquals(java.nio.file.Paths.get(result.out.trim), destination)
    assertEquals(Files.readAllBytes(destination).toSeq, s.executable.toSeq)
  }

  directory.test("classpath resources already copied to the cache are not copied again") { root =>
    val s = sandbox(root)
    val installed = s.preinstall(s.classpathVersion)
    val result = succeeded(s.launch(envVersion = None, extraClasspath = Seq(s.classpathResources())))
    checkLog(result.err,
      has(s"Firtool version ${s.classpathVersion} found in resources"),
      rx("Firtool binary .*firtool already exists"))
    assertAbsent(result.err, "Copying firtool from resources")
    assertEquals(java.nio.file.Paths.get(result.out.trim), installed)
  }

  // ---- Explicit version overrides (version_override.sh) ----

  directory.test("environment version override uses the cached firtool and skips the classpath") { root =>
    val s = sandbox(root)
    val installed = s.preinstall()
    val result = succeeded(s.launch(arg = "0.0.0", envVersion = Some(s.version)))
    checkLog(result.err,
      has(s"Using explicit firtool version override: ${s.version}"),
      rx(s"Firtool binary with version \\(${java.util.regex.Pattern.quote(s.version)}\\) .* already exists"))
    assertAbsent(result.err, "Checking resources for firtool")
    assertEquals(java.nio.file.Paths.get(result.out.trim), installed)
    if (!windows) assertEquals(s.runFirtool(installed), s"CIRCT firtool-${s.version}")
  }

  directory.test("property version override wins over environment and classpath") { root =>
    val s = sandbox(root)
    val installed = s.preinstall()
    val result = succeeded(s.launch(
      arg = "0.0.1",
      envVersion = Some("0.0.0"),
      javaOpts = Seq(s"-Dchisel.firtool.version=${s.version}"),
      extraClasspath = Seq(s.classpathResources())))
    checkLog(result.err,
      has(s"Using explicit firtool version override: ${s.version}"),
      has(s"Firtool version override ${s.version} takes precedence over llvm-firtool ${s.classpathVersion} " +
        "on the classpath"),
      rx(s"Firtool binary with version \\(${java.util.regex.Pattern.quote(s.version)}\\) .* already exists"))
    assertAbsent(result.err, "Checking resources for firtool", "Copying firtool from resources")
    assertEquals(java.nio.file.Paths.get(result.out.trim), installed)
    assert(!Files.exists(s.binary(s.classpathVersion)))
  }

  // ---- Maven fallback (coursier_fallback.sh) ----

  directory.test("falls back to Maven when GitHub fails") { root =>
    val s = sandbox(root)
    s.publishMaven()
    val result = succeeded(s.launch(arg = s.version, envVersion = None, envURL = Some("file:///nonexistent")))
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      has("chisel.firtool.path and CHISEL_FIRTOOL_PATH not set"),
      has("Checking resources for firtool"),
      has("firtool version not found in resources"),
      has(s"Firtool binary with version (${s.version}) does not already exist"),
      has(s"Attempting to fetch firtool ${s.version} from GitHub releases"),
      has(s"Fetching firtool ${s.version} from GitHub failed:"),
      has(s"Attempting to fetch org.chipsalliance:llvm-firtool:${s.version}"),
      has("Successfully fetched"),
      has("Loading"),
      has("to search its resources"),
      has("Checking resources for firtool"),
      has(s"Firtool version ${s.version} found in resources"))
    assertEquals(java.nio.file.Paths.get(result.out.trim), s.binary())
    assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
  }

  directory.test("Maven fallback honors the override despite a different classpath version") { root =>
    val s = sandbox(root)
    s.publishMaven()
    val result = succeeded(s.launch(extraClasspath = Seq(s.classpathResources()), envURL = Some("file:///nonexistent")))
    assert(result.err.contains(s"Firtool version ${s.version} found in resources"), result.output)
    assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
    assert(!Files.exists(s.cache.resolve(s.classpathVersion)))
  }

  // ---- Platform fallback (platform_fallback.sh) ----

  directory.test("macOS arm64 falls back to the x64 release asset when no arm64 asset is published") { root =>
    val s = sandbox(root, Seq("macos-x64"))
    val result = succeeded(s.launch(arg = s.version, envVersion = None, javaOpts = Host.macArm64))
    checkLog(result.err,
      rx("Downloading SHA-256 checksum from .*firrtl-bin-macos-arm64\\.tar\\.gz\\.sha256"),
      has(s"No GitHub release asset for firtool ${s.version} on macos-aarch64"),
      has("Falling back to platform macos-x64"),
      has(s"Downloading firtool ${s.version} for macos-x64 from"),
      has("Verified SHA-256 checksum:"))
    assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
  }

  directory.test("macOS arm64 uses the native release asset when one is published") { root =>
    val s = sandbox(root, Seq("macos-aarch64", "macos-x64"))
    val result = succeeded(s.launch(arg = s.version, envVersion = None, javaOpts = Host.macArm64))
    checkLog(result.err, has(s"Downloading firtool ${s.version} for macos-aarch64 from"))
    assertAbsent(result.err, "Falling back to platform")
  }

  directory.test("reports every platform tried when no release asset is published") { root =>
    val s = sandbox(root, Seq("linux-x64"))
    val result = failed(s.launch(arg = s.version, envVersion = None, javaOpts = Host.macArm64))
    assert(result.err.contains(s"No GitHub release asset for firtool ${s.version} on macos-aarch64 or macos-x64"),
      result.output)
  }

  directory.test("macOS arm64 falls back to x64 resources in Maven artifacts") { root =>
    val s = sandbox(root, Nil)
    s.publishMaven(forPlatforms = Seq("macos-x64"))
    val result = succeeded(s.launch(arg = s.version, envVersion = None, javaOpts = Host.macArm64))
    checkLog(result.err,
      has("Falling back to platform macos-x64"),
      has(s"Firtool version ${s.version} found in resources"),
      has("Falling back to macos-x64 resources"))
    assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
  }

  // ---- CHISEL_FIRTOOL_PATH (firtool_path.sh) ----

  private def firtoolDirectory(s: Sandbox, content: Array[Byte]): Path = {
    val dir = s.root.resolve("firtool-dir")
    write(dir.resolve("firtool"), content)
    dir.resolve("firtool").toFile.setExecutable(true)
    dir
  }

  // The fixture is a shell script, which cannot be run directly on Windows.
  directory.test("CHISEL_FIRTOOL_PATH selects the firtool without fetching anything") { root =>
    assume(!windows, "the fake firtool is a shell script")
    val s = sandbox(root)
    val dir = firtoolDirectory(s, s.script("CIRCT firtool-1.2.3"))
    val result = succeeded(s.launch(arg = s.version, envVersion = None, envPath = Some(dir.toString)))
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      rx("Running: .+firtool --version"))
    assertAbsent(result.err,
      "chisel.firtool.path and CHISEL_FIRTOOL_PATH not set", "Checking resources for firtool", "Attempting to fetch")
    assertEquals(java.nio.file.Paths.get(result.out.trim), dir.resolve("firtool").toAbsolutePath)
    assert(!Files.exists(s.cache))
  }

  directory.test("CHISEL_FIRTOOL_PATH failures are reported rather than falling back to the default version") { root =>
    assume(!windows, "the fake firtool is a shell script")
    val s = sandbox(root)
    val dir = firtoolDirectory(s, s.script("CIRCT firtool-1.2.3"))
    // Mirrors the old script: make the binary disappear after it worked.
    Files.move(dir.resolve("firtool"), dir.resolve("firtool_renamed"))
    val result = failed(s.launch(arg = s.version, envVersion = None, envPath = Some(dir.toString)))
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      rx("Running: .+firtool --version"),
      rx("Cannot run program .+firtool"))
    assertAbsent(result.err,
      "chisel.firtool.path and CHISEL_FIRTOOL_PATH not set", "Checking resources for firtool", "Attempting to fetch")
    assert(!Files.exists(s.cache))
  }

  directory.test("CHISEL_FIRTOOL_PATH with a firtool that exits non-zero is an error") { root =>
    assume(!windows, "the fake firtool is a shell script")
    val s = sandbox(root)
    val dir = firtoolDirectory(s, s.script("boom", exitCode = 3))
    val result = failed(s.launch(arg = s.version, envVersion = None, envPath = Some(dir.toString)))
    assert(result.err.contains("Unable to run firtool binary"), result.output)
    assert(result.err.contains("Exit Code: 3"), result.output)
    assertAbsent(result.err, "Attempting to fetch")
  }

  directory.test("CHISEL_FIRTOOL_PATH with an unparseable version still works") { root =>
    assume(!windows, "the fake firtool is a shell script")
    val s = sandbox(root)
    val dir = firtoolDirectory(s, s.script("not a version"))
    succeeded(s.launch(arg = s.version, envVersion = None, envPath = Some(dir.toString)))
  }

  // ---- System properties and environment variables ----

  directory.test("chisel.firtool.cache property takes precedence over CHISEL_FIRTOOL_CACHE") { root =>
    val s = sandbox(root)
    val propCache = root.resolve("prop-cache")
    val envCache = root.resolve("env-cache")
    succeeded(s.launch(javaOpts = Seq(s"-Dchisel.firtool.cache=$propCache"), envCache = Some(envCache.toString)))
    assert(Files.exists(propCache.resolve(s"${s.version}/bin/firtool")))
    assert(!Files.exists(envCache))
  }

  directory.test("chisel.firtool.cache property works without CHISEL_FIRTOOL_CACHE") { root =>
    val s = sandbox(root)
    val propCache = root.resolve("prop-cache")
    succeeded(s.launch(javaOpts = Seq(s"-Dchisel.firtool.cache=$propCache"), envCache = None))
    assert(Files.exists(propCache.resolve(s"${s.version}/bin/firtool")))
  }

  directory.test("chisel.firtool.url property takes precedence over CHISEL_FIRTOOL_URL") { root =>
    val s = sandbox(root)
    val result = succeeded(s.launch(
      javaOpts = Seq(s"-Dchisel.firtool.url=${s.base.toUri}"), envURL = Some("file:///nonexistent")))
    assert(Files.exists(s.binary()), result.output)
  }

  directory.test("chisel.firtool.path property takes precedence over CHISEL_FIRTOOL_PATH") { root =>
    val s = sandbox(root)
    val propPath = root.resolve("missing-prop-path")
    val envPath = root.resolve("missing-env-path")
    val result = failed(
      s.launch(javaOpts = Seq(s"-Dchisel.firtool.path=$propPath"), envPath = Some(envPath.toString)))
    assert(result.err.contains(s"chisel.firtool.path (${propPath.toAbsolutePath}) does not exist"), result.output)
    assertAbsent(result.err, "Attempting to fetch")
    assert(!Files.exists(s.cache))
  }

  directory.test("CHISEL_FIRTOOL_PATH is reported when the property is unset") { root =>
    val s = sandbox(root)
    val envPath = root.resolve("missing-env-path")
    val result = failed(s.launch(envPath = Some(envPath.toString)))
    assert(result.err.contains(s"CHISEL_FIRTOOL_PATH (${envPath.toAbsolutePath}) does not exist"), result.output)
    assert(!Files.exists(s.cache))
  }
}

/** Run the resolver from the unshaded compiled classes. */
class ResolverTests extends ResolverSuite("firtoolresolver.test.classpath")

/** Run the resolver as users get it: the shaded assembly plus only its dynamic Scala dependencies. */
class ShadedResolverTests extends ResolverSuite("firtoolresolver.test.shaded.classpath")
