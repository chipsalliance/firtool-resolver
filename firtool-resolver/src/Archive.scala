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

  private def extractTar(input: InputStream, matches: String => Boolean, destination: Path): Unit = {
    // Commons Compress handles entry boundaries, padding, PAX, and GNU long names.
    val tar = new TarArchiveInputStream(input)
    try {
      var entry = tar.getNextTarEntry()
      while (entry != null) {
        if (entry.isFile && !entry.isLink && !entry.isSymbolicLink && matches(entry.getName)) {
          if (!tar.canReadEntryData(entry)) throw new IOException(s"Unsupported tar entry: ${entry.getName}")
          Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
          return
        }
        entry = tar.getNextTarEntry()
      }
      throw new IOException("Tar archive does not contain the firtool binary")
    } finally tar.close()
  }
}
