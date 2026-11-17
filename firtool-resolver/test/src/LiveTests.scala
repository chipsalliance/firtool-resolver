// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import coursier._
import coursier.core.Extension
import java.nio.file.{Files, Path, Paths}
import Check._

/** Tests of what hermetic fixtures cannot prove: the real GitHub releases, Maven Central, and real firtool binaries.
  *
  * These are skipped unless FIRTOOL_RESOLVER_LIVE_TESTS is set, since they need network access (and download
  * a few hundred MB). They run the resolver as published, i.e. from the shaded classpath.
  *
  * llvm-firtool is no longer published, so use a version already on Maven Central that is also a GitHub release.
  */
class LiveTests extends TemporaryDirectorySuite with LogAssertions {
  private val firtoolVersion = "1.162.0"
  private def enabled = sys.env.contains("FIRTOOL_RESOLVER_LIVE_TESTS")

  private def live(name: String)(body: Sandbox => Unit): Unit =
    directory.test(name) { root =>
      assume(enabled, "set FIRTOOL_RESOLVER_LIVE_TESTS to run tests that need network access")
      body(new Sandbox(root, "firtoolresolver.test.shaded.classpath"))
    }

  private def resolved(result: Result): Path = {
    assertEquals(result.exit, 0, result.output)
    Paths.get(result.out.trim)
  }

  // Real firtool prints an LLVM banner before its CIRCT version line, and some releases append a git describe suffix.
  private def assertRealFirtool(s: Sandbox, binary: Path, version: String = firtoolVersion): Unit = {
    val output = s.runFirtool(binary)
    assert(output.linesIterator.exists(_.matches(s"CIRCT firtool-${java.util.regex.Pattern.quote(version)}(-.*)?")),
      output)
  }

  live("downloads a real release from GitHub") { s =>
    val result = s.launch(arg = firtoolVersion, envVersion = None, envURL = None, envRepos = None).await()
    val binary = resolved(result)
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      has("chisel.firtool.path and CHISEL_FIRTOOL_PATH not set"),
      has("Checking resources for firtool"),
      has("firtool version not found in resources"),
      has(s"Firtool binary with version ($firtoolVersion) does not already exist"),
      has(s"Attempting to fetch firtool $firtoolVersion from GitHub releases"),
      has("Acquiring firtool cache lock"),
      has("Downloading SHA-256 checksum"),
      has(s"Downloading firtool $firtoolVersion for"),
      has("Extracting firtool from"),
      has("Verified SHA-256 checksum:"),
      has("Atomically moving"))
    assertAbsent(result.err, "Attempting to fetch org.chipsalliance:llvm-firtool:")
    assertRealFirtool(s, binary)

    // The explicit override finds what was just downloaded.
    val again = s.launch(arg = "0.0.0", envVersion = Some(firtoolVersion), envURL = None, envRepos = None).await()
    assertEquals(resolved(again), binary)
    checkLog(again.err, has(s"Using explicit firtool version override: $firtoolVersion"), has("already exists"))
    assertAbsent(again.err, "Checking resources for firtool")
  }

  live("falls back to the real llvm-firtool on Maven Central when GitHub fails") { s =>
    val result = s.launch(
      arg = firtoolVersion, envVersion = None, envURL = Some("file:///nonexistent"), envRepos = Some("central")).await()
    val binary = resolved(result)
    checkLog(result.err,
      has(s"Fetching firtool $firtoolVersion from GitHub failed:"),
      has(s"Attempting to fetch org.chipsalliance:llvm-firtool:$firtoolVersion"),
      has("Successfully fetched"),
      has("to search its resources"),
      has(s"Firtool version $firtoolVersion found in resources"))
    assertRealFirtool(s, binary)
  }

  live("copies firtool from the real llvm-firtool jar on the classpath") { s =>
    def fetch(platform: String): Seq[java.io.File] = {
      val dep = Dependency(Module(Organization("org.chipsalliance"), ModuleName("llvm-firtool")), firtoolVersion)
        .withPublication("", Type.empty, Extension.empty, Classifier(platform))
      Fetch().addDependencies(dep).run()
    }
    val jar = scala.util.Try(fetch(Host.platform)).getOrElse(fetch("macos-x64"))
    val result = s.launch(
      arg = firtoolVersion, envVersion = None, envURL = Some("file:///nonexistent"), envRepos = None,
      extraClasspath = jar.map(_.toPath).take(1)).await()
    val binary = resolved(result)
    checkLog(result.err,
      has("Checking resources for firtool"),
      has(s"Firtool version $firtoolVersion found in resources"),
      rx("Copying firtool from resources to .*firtool"),
      has(s"using llvm-firtool $firtoolVersion from classpath; this mechanism is deprecated, " +
        "set chisel.firtool.version instead"))
    assertRealFirtool(s, binary)
  }

  live("a real firtool in CHISEL_FIRTOOL_PATH is used") { s =>
    val downloaded = resolved(s.launch(arg = firtoolVersion, envVersion = None, envURL = None, envRepos = None).await())
    val result = s.launch(
      arg = firtoolVersion, envVersion = None, envURL = Some("file:///nonexistent"), envRepos = None,
      envPath = Some(downloaded.getParent.toString)).await()
    assertEquals(resolved(result), downloaded)
    checkLog(result.err,
      has("Checking chisel.firtool.path and CHISEL_FIRTOOL_PATH for firtool"),
      rx("Running: .+firtool --version"))
    assertAbsent(result.err, "Checking resources for firtool", "Attempting to fetch")
  }

  // CIRCT did not publish arm64 macOS binaries for this version, so only the x64 asset can be used.
  live("macOS arm64 falls back to the x64 asset for a release without an arm64 asset") { s =>
    assume(Host.mac && Host.aarch64, "only meaningful on macOS arm64")
    val result = s.launch(arg = "1.143.0", envVersion = None, envURL = None, envRepos = None).await()
    val binary = resolved(result)
    checkLog(result.err,
      rx("Downloading SHA-256 checksum from .*firrtl-bin-macos-arm64\\.tar\\.gz\\.sha256"),
      has("No GitHub release asset for firtool 1.143.0 on macos-aarch64"),
      has("Falling back to platform macos-x64"),
      has("Downloading firtool 1.143.0 for macos-x64 from"),
      has("Verified SHA-256 checksum:"))
    assertRealFirtool(s, binary, "1.143.0")
  }
}
