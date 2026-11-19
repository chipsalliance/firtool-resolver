// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters._

/** Sanity checks of the published jar. */
class JarTests extends munit.FunSuite {
  private lazy val entries: Seq[String] = {
    val jar = System.getProperty("firtoolresolver.test.jar")
    assert(jar != null, "System property firtoolresolver.test.jar is not set")
    val zip = new ZipFile(jar)
    try zip.entries().asScala.map(_.getName).toVector finally zip.close()
  }

  test("the jar does not bundle scala-library or other Scala jars") {
    // Scala is a dynamic dependency, bundling it would conflict with the user's Scala version.
    val scalaEntries = entries.filter(_.startsWith("scala"))
    assert(scalaEntries.isEmpty, s"unexpected scala entries:\n${scalaEntries.take(10).mkString("\n")}")
  }

  test("the jar contains the resolver classes") {
    assert(entries.exists(_.startsWith("firtoolresolver/Main")), entries.take(20).toString)
    assert(entries.contains("firtoolresolver/Resolve$.class"), entries.take(20).toString)
  }

  test("dependencies are shaded and not left at their original packages") {
    // Check every class, including any introduced by new transitive dependencies.
    val leaked = entries.filter(e => e.endsWith(".class") && !e.startsWith("firtoolresolver/"))
    assert(leaked.isEmpty, s"unshaded entries:\n${leaked.take(10).mkString("\n")}")
    Seq("dev/dirs/", "org/apache/commons/compress/").foreach { pkg =>
      assert(entries.exists(e => e.startsWith(s"firtoolresolver/shaded/$pkg") && e.endsWith(".class")),
        s"missing shaded classes for $pkg")
    }
    assert(!entries.exists(_.contains("coursier/")), "jar contains Coursier classes")
  }
}
