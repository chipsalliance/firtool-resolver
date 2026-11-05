// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.io.{EOFException, IOException, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{GZIPInputStream, ZipInputStream}

/** Extract just the regular firtool entry. Archive paths are never used as destination paths. */
private[firtoolresolver] object Archive {
  def extract(archive: Path, windows: Boolean, destination: Path): Unit = {
    val name = if (windows) "firtool.exe" else "firtool"
    def matches(path: String): Boolean = path == s"bin/$name" || path.endsWith(s"/bin/$name")
    val input = Files.newInputStream(archive)
    try {
      if (windows) {
        val zip = new ZipInputStream(input)
        try {
          var entry = zip.getNextEntry()
          while (entry != null) {
            if (!entry.isDirectory && matches(entry.getName)) {
              Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING)
              return
            }
            zip.closeEntry()
            entry = zip.getNextEntry()
          }
        } finally zip.close()
      } else {
        val gzip = new GZIPInputStream(input)
        try extractTar(gzip, matches, destination)
        finally gzip.close()
        return
      }
      throw new IOException(s"Archive does not contain bin/$name: $archive")
    } finally input.close()
  }

  private def readFully(input: InputStream, bytes: Array[Byte]): Unit = {
    var offset = 0
    while (offset < bytes.length) {
      val count = input.read(bytes, offset, bytes.length - offset)
      if (count < 0) throw new EOFException("Truncated tar archive")
      offset += count
    }
  }

  private def skipFully(input: InputStream, length: Long): Unit = {
    var remaining = length
    while (remaining > 0) {
      val skipped = input.skip(remaining)
      if (skipped > 0) remaining -= skipped
      else if (input.read() < 0) throw new EOFException("Truncated tar entry")
      else remaining -= 1
    }
  }

  private def field(header: Array[Byte], offset: Int, length: Int): String = {
    val end = (offset until offset + length).find(header(_) == 0).getOrElse(offset + length)
    new String(header, offset, end - offset, UTF_8)
  }

  // PAX records are length-prefixed in bytes, not characters. Only the path key matters here.
  private def paxPath(bytes: Array[Byte]): Option[String] = {
    var offset = 0
    var path = Option.empty[String]
    while (offset < bytes.length) {
      val space = (offset until bytes.length).find(bytes(_) == ' '.toByte)
        .getOrElse(throw new IOException("Invalid PAX record"))
      val length = try new String(bytes, offset, space - offset, UTF_8).toInt
      catch { case _: NumberFormatException => throw new IOException("Invalid PAX record length") }
      if (length <= space - offset + 1 || length > bytes.length - offset) {
        throw new IOException("Invalid PAX record length")
      }
      val record = new String(bytes, space + 1, offset + length - space - 1, UTF_8).stripSuffix("\n")
      if (record.startsWith("path=")) path = Some(record.stripPrefix("path="))
      offset += length
    }
    path
  }

  private def extractTar(input: InputStream, matches: String => Boolean, destination: Path): Unit = {
    val header = new Array[Byte](512)
    var extendedPath = Option.empty[String]
    var globalPath = Option.empty[String]
    while (true) {
      readFully(input, header)
      if (header.forall(_ == 0)) throw new IOException("Tar archive does not contain the firtool binary")
      val sizeField = field(header, 124, 12).trim
      val size = try java.lang.Long.parseLong(sizeField, 8)
      catch { case _: NumberFormatException => throw new IOException(s"Invalid tar entry size: $sizeField") }
      if (size < 0) throw new IOException("Negative tar entry size")
      val prefix = field(header, 345, 155)
      val name = field(header, 0, 100)
      val path = extendedPath.orElse(globalPath).getOrElse(if (prefix.isEmpty) name else s"$prefix/$name")
      val kind = header(156).toChar
      if (kind == 'x' || kind == 'g' || kind == 'L') {
        // Bound metadata allocation, and skip all other metadata keys (including PAX size).
        if (size > 1024 * 1024) throw new IOException("Tar extended header is too large")
        val bytes = new Array[Byte](size.toInt)
        readFully(input, bytes)
        if (kind == 'L') extendedPath = Some(new String(bytes, UTF_8).takeWhile(_ != '\u0000').stripSuffix("\n"))
        else if (kind == 'x') extendedPath = paxPath(bytes).orElse(extendedPath)
        else globalPath = paxPath(bytes).orElse(globalPath)
      } else {
        extendedPath = None
        if ((kind == '0' || kind == '\u0000') && matches(path)) {
          val bounded = new InputStream {
            private var remaining = size
            override def read(): Int = {
              val buffer = new Array[Byte](1)
              if (read(buffer, 0, 1) < 0) -1 else buffer(0) & 0xff
            }
            override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
              if (length == 0) return 0
              if (remaining == 0) return -1
              val count = input.read(bytes, offset, math.min(remaining, length.toLong).toInt)
              if (count < 0) throw new EOFException("Truncated firtool tar entry")
              remaining -= count
              count
            }
          }
          Files.copy(bounded, destination, StandardCopyOption.REPLACE_EXISTING)
          return
        }
        skipFully(input, size)
      }
      skipFully(input, (512 - size % 512) % 512)
    }
  }
}
