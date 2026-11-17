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

/** FileCheck-style assertions over a log: `has`/`rx` must match in order, `not` must not match in between. */
private[firtoolresolver] sealed trait Check
private[firtoolresolver] object Check {
  final case class Has(pattern: java.util.regex.Pattern) extends Check
  final case class Not(pattern: java.util.regex.Pattern) extends Check

  /** Literal substring (like CHECK). */
  def has(text: String): Check = Has(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text)))

  /** Regular expression (like CHECK with {{...}}). */
  def rx(regex: String): Check = Has(java.util.regex.Pattern.compile(regex))

  /** Literal substring that must not appear before the next `has`/`rx` match, or the end of the log (CHECK-NOT). */
  def not(text: String): Check = Not(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text)))
}

private[firtoolresolver] trait LogAssertions { self: munit.FunSuite =>
  import Check._

  def assertAbsent(log: String, texts: String*)(implicit loc: munit.Location): Unit =
    texts.foreach(t => assert(!log.contains(t), s"unexpected '$t' in:\n$log"))

  def checkLog(log: String, checks: Check*)(implicit loc: munit.Location): Unit = {
    var position = 0
    var pending = List.empty[java.util.regex.Pattern]
    def noPending(end: Int): Unit = {
      val region = log.substring(position, end)
      pending.reverse.foreach { p =>
        if (p.matcher(region).find()) fail(s"unexpected match for ${p.pattern} in:\n$region\n--- full log ---\n$log")
      }
      pending = Nil
    }
    checks.foreach {
      case Not(p) => pending ::= p
      case Has(p) =>
        val matcher = p.matcher(log)
        if (!matcher.find(position)) {
          fail(s"expected match for ${p.pattern} after offset $position\n--- full log ---\n$log")
        }
        noPending(matcher.start)
        position = matcher.end
    }
    noPending(log.length)
  }
}
