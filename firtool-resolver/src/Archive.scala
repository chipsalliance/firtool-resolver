// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.io.{IOException, InputStream}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{GZIPInputStream, ZipInputStream}
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

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
          entries(zip.getNextEntry()).find(e => !e.isDirectory && matches(e.getName)) match {
            case Some(_) => Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING)
            case None    => throw new IOException(s"Archive does not contain bin/$name: $archive")
          }
        } finally zip.close()
      } else {
        val gzip = new GZIPInputStream(input)
        try extractTar(gzip, matches, destination)
        finally gzip.close()
      }
    } finally input.close()
  }

  /** Lazily iterate entries until the stream returns null. */
  private def entries[A >: Null](next: => A): Iterator[A] = Iterator.continually(next).takeWhile(_ != null)

  private def extractTar(input: InputStream, matches: String => Boolean, destination: Path): Unit = {
    // Commons Compress handles entry boundaries, padding, PAX, and GNU long names.
    val tar = new TarArchiveInputStream(input)
    try {
      val found = entries(tar.getNextTarEntry()).find { e =>
        e.isFile && !e.isLink && !e.isSymbolicLink && matches(e.getName)
      }
      found match {
        case Some(e) =>
          if (!tar.canReadEntryData(e)) throw new IOException(s"Unsupported tar entry: ${e.getName}")
          Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
        case None =>
          throw new IOException("Tar archive does not contain the firtool binary")
      }
    } finally tar.close()
  }
}
