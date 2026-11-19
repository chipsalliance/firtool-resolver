// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}
import scala.jdk.CollectionConverters._

/** Builders for local release mirrors and Maven fixtures. */
private[firtoolresolver] object TestFixtures {
  def bytes(value: String): Array[Byte] = value.getBytes(UTF_8)

  def write(path: Path, value: Array[Byte]): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value)
  }

  case class Entry(name: String, data: Array[Byte])

  def tar(path: Path, entries: Seq[Entry]): Unit = {
    val output = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))
    try entries.foreach { fixture =>
      val entry = new TarArchiveEntry(fixture.name)
      entry.setSize(fixture.data.length.toLong)
      output.putArchiveEntry(entry)
      output.write(fixture.data)
      output.closeArchiveEntry()
    } finally output.close()
  }

  // ZIP is also used to build the legacy Maven jar fixture.
  def zip(path: Path, entries: Seq[Entry]): Unit = {
    val output = new ZipOutputStream(Files.newOutputStream(path))
    try entries.foreach { entry =>
      output.putNextEntry(new ZipEntry(entry.name))
      output.write(entry.data)
      output.closeEntry()
    } finally output.close()
  }

  def digest(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path)).map(b => f"${b & 0xff}%02x").mkString
}

/** Every test gets its own temporary directory, cleaned up even on failure. */
private[firtoolresolver] trait TemporaryDirectorySuite extends munit.FunSuite {
  protected val directory = FunFixture[Path](
    setup = _ => Files.createTempDirectory("firtool-resolver-tests-"),
    teardown = root => {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally paths.close()
    }
  )
}

/** FileCheck-style assertions over a log: `has`/`rx` must match in order. */
private[firtoolresolver] final case class Check(pattern: java.util.regex.Pattern)
private[firtoolresolver] object Check {

  /** Literal substring (like CHECK). */
  def has(text: String): Check = rx(java.util.regex.Pattern.quote(text))

  /** Regular expression (like CHECK with {{...}}). */
  def rx(regex: String): Check = Check(java.util.regex.Pattern.compile(regex))
}

private[firtoolresolver] trait LogAssertions { self: munit.FunSuite =>
  def assertAbsent(log: String, texts: String*)(implicit loc: munit.Location): Unit =
    texts.foreach(t => assert(!log.contains(t), s"unexpected '$t' in:\n$log"))

  def checkLog(log: String, checks: Check*)(implicit loc: munit.Location): Unit = {
    var position = 0
    checks.foreach { check =>
      val matcher = check.pattern.matcher(log)
      if (!matcher.find(position)) {
        fail(s"expected match for ${check.pattern} after offset $position\n--- full log ---\n$log")
      }
      position = matcher.end
    }
  }
}
