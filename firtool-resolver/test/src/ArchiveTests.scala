// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import TestFixtures._

class ArchiveTests extends TemporaryDirectorySuite {
  private val payload = bytes("the firtool payload")

  for ((label, name, mode) <- Seq(
    ("standard name", "release/bin/firtool", TarArchiveOutputStream.LONGFILE_POSIX),
    ("GNU long name", s"${"release/" * 30}bin/firtool", TarArchiveOutputStream.LONGFILE_GNU),
    ("PAX long UTF-8 name", s"${"rélease/" * 30}bin/firtool", TarArchiveOutputStream.LONGFILE_POSIX)
  )) {
    directory.test(s"extract firtool from a tar archive with $label") { root =>
      val archive = root.resolve("release.tar.gz")
      tar(archive, Seq(Entry("other", bytes("skip")), Entry(name, payload)), mode)
      val destination = root.resolve("extracted")
      Archive.extract(archive, false, destination)
      assertEquals(Files.readAllBytes(destination).toSeq, payload.toSeq)
    }
  }

  directory.test("skip symbolic links and extract the regular firtool entry") { root =>
    val archive = root.resolve("release.tar.gz")
    tar(archive, Seq(Entry("release/bin/firtool", Array.emptyByteArray, symbolicLink = true),
      Entry("actual/bin/firtool", payload)))
    val destination = root.resolve("extracted")
    Archive.extract(archive, false, destination)
    assertEquals(Files.readAllBytes(destination).toSeq, payload.toSeq)
  }

  directory.test("fail when a tar archive contains no firtool") { root =>
    val archive = root.resolve("release.tar.gz")
    tar(archive, Seq(Entry("unrelated", payload)))
    intercept[IOException](Archive.extract(archive, false, root.resolve("extracted")))
  }

  directory.test("fail on a truncated download") { root =>
    val archive = root.resolve("release.tar.gz")
    tar(archive, Seq(Entry("release/bin/firtool", payload)))
    val content = Files.readAllBytes(archive)
    Files.write(archive, content.take(content.length / 2))
    intercept[IOException](Archive.extract(archive, false, root.resolve("extracted")))
  }

  directory.test("fail on malformed tar headers") { root =>
    val archive = root.resolve("release.tar.gz")
    val output = new GZIPOutputStream(Files.newOutputStream(archive))
    try output.write(Array.fill[Byte](512)('!'.toByte)) finally output.close()
    intercept[IOException](Archive.extract(archive, false, root.resolve("extracted")))
  }

  directory.test("extract only firtool.exe from a Windows ZIP archive") { root =>
    val archive = root.resolve("release.zip")
    zip(archive, Seq(Entry("ignored", bytes("skip")), Entry("release/bin/firtool.exe", payload)))
    val destination = root.resolve("extracted")
    Archive.extract(archive, true, destination)
    assertEquals(Files.readAllBytes(destination).toSeq, payload.toSeq)
    val paths = Files.list(root)
    try assertEquals(paths.count(), 2L) finally paths.close()
  }
}
