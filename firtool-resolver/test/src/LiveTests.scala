// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import coursier._
import coursier.core.Extension
import java.nio.file.{Files, Path, Paths}
import Check._

/** Tests of what hermetic fixtures cannot prove: the real GitHub releases, Maven Central, and real firtool binaries.
  *
  * These are tagged "live" and need network access (and download a few hundred MB).
  * They run the resolver as published, i.e. from the shaded classpath.
  *
  * llvm-firtool is no longer published, so use a version already on Maven Central that is also a GitHub release.
  */
class LiveTests extends TemporaryDirectorySuite with LogAssertions {
  private val firtoolVersion = "1.162.0"
  private val Live = new munit.Tag("live")

  private def live(name: String)(body: Sandbox => Unit): Unit =
    directory.test(name.tag(Live)) { root =>
      body(new Sandbox(root, "firtoolresolver.test.shaded.classpath"))
    }

  private def resolved(result: Result): Path = {
    assertEquals(result.exit, 0, result.output)
    Paths.get(result.out.trim)
  }

  // Real firtool prints an LLVM banner before its CIRCT version line, and some releases append a git describe suffix.
  private def assertRealFirtool(s: Sandbox, binary: Path): Unit = {
    val output = s.runFirtool(binary)
    assert(output.linesIterator.exists(_.matches(s"CIRCT firtool-${java.util.regex.Pattern.quote(firtoolVersion)}(-.*)?")),
      output)
  }

  live("downloads a real release from GitHub") { s =>
    val result = s.launch(arg = firtoolVersion, envVersion = None, envURL = None, envRepos = None).await()
    val binary = resolved(result)
    checkLog(result.err, has("Verified SHA-256 checksum:"))
    assertAbsent(result.err, "Attempting to fetch org.chipsalliance:llvm-firtool:")
    assertRealFirtool(s, binary)

  }

  live("falls back to the real llvm-firtool on Maven Central when GitHub fails") { s =>
    val result = s.launch(
      arg = firtoolVersion, envVersion = None, envURL = Some("file:///nonexistent"), envRepos = Some("central")).await()
    val binary = resolved(result)
    checkLog(result.err,
      has(s"Attempting to fetch org.chipsalliance:llvm-firtool:$firtoolVersion"),
      has("Successfully fetched"))
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
    checkLog(result.err, has(s"using llvm-firtool $firtoolVersion from classpath"))
    assertRealFirtool(s, binary)
  }
}
