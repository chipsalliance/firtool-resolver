// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.nio.file.Paths
import Check._

class LiveTests extends TemporaryDirectorySuite with LogAssertions {
  private val Live = new munit.Tag("live")

  directory.test("downloads and runs a real GitHub release".tag(Live)) { root =>
    val version = "1.162.0"
    val sandbox = new Sandbox(root, "firtoolresolver.test.shaded.classpath")
    val result = sandbox.launch(arg = version, envVersion = None, envURL = None).await()
    assertEquals(result.exit, 0, result.output)
    checkLog(result.err, has("Verified SHA-256 checksum:"))
    val output = sandbox.runFirtool(Paths.get(result.out.trim))
    assert(output.linesIterator.exists(_.matches(s"CIRCT firtool-${java.util.regex.Pattern.quote(version)}(-.*)?")),
      output)
  }
}
