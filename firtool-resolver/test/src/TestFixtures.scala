// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream, TarConstants}
import scala.jdk.CollectionConverters._

/** Archive builders shared by the extraction and local-mirror tests. */
private[firtoolresolver] object TestFixtures {
  def bytes(value: String): Array[Byte] = value.getBytes(UTF_8)

  def write(path: Path, value: Array[Byte]): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value)
  }

  case class Entry(name: String, data: Array[Byte], symbolicLink: Boolean = false)

  def tar(path: Path, entries: Seq[Entry], longFileMode: Int = TarArchiveOutputStream.LONGFILE_POSIX): Unit = {
    val output = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(path)), "UTF-8")
    try {
      output.setLongFileMode(longFileMode)
      output.setAddPaxHeadersForNonAsciiNames(true)
      entries.foreach { fixture =>
        val entry = if (fixture.symbolicLink) {
          val link = new TarArchiveEntry(fixture.name, TarConstants.LF_SYMLINK)
          link.setLinkName("elsewhere")
          link
        } else {
          val file = new TarArchiveEntry(fixture.name)
          file.setSize(fixture.data.length.toLong)
          file
        }
        output.putArchiveEntry(entry)
        if (!fixture.symbolicLink) output.write(fixture.data)
        output.closeArchiveEntry()
      }
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
