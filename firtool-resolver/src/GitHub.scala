// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.io.{FileNotFoundException, IOException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.{DigestInputStream, MessageDigest}
import java.util.Locale
import java.util.zip.GZIPInputStream
import scala.io.Source
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

/** Download CIRCT release assets and extract just the firtool executable. */
private[firtoolresolver] object GitHub {
  private class NotFound(url: URL) extends IOException(s"Not found: $url")

  /** A published release asset for one platform, with its expected SHA-256. */
  final case class Release(version: String, platform: String, url: URL, sha256: String)

  private def open(url: URL): InputStream = {
    val connection = url.openConnection()
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(60000)
    connection.setRequestProperty("User-Agent", "firtool-resolver")
    // HttpURLConnection follows same-protocol redirects (to release-assets.githubusercontent.com).
    connection match {
      case http: HttpURLConnection if http.getResponseCode == 404 => throw new NotFound(url)
      case http: HttpURLConnection if http.getResponseCode != 200 =>
        throw new IOException(s"HTTP ${http.getResponseCode} downloading $url")
      case _ =>
    }
    try connection.getInputStream
    catch { case _: FileNotFoundException => throw new NotFound(url) } // file: URLs
  }

  /** Look up the release asset and its checksum. None means the asset is not published for this platform. */
  def lookup(logger: Logger, version: String, platform: String): Option[Release] = {
    val circtPlatform = if (platform == "macos-aarch64") "macos-arm64" else platform
    val extension = if (platform.startsWith("windows-")) "zip" else "tar.gz"
    val base = sys.props.get("chisel.firtool.url")
      .orElse(sys.env.get("CHISEL_FIRTOOL_URL"))
      .getOrElse("https://github.com/llvm/circt/releases/download")
    val url = new URL(s"${base.stripSuffix("/")}/firtool-$version/firrtl-bin-$circtPlatform.$extension")
    logger.debug(s"Downloading SHA-256 checksum from $url.sha256")
    try {
      val source = Source.fromInputStream(open(new URL(s"$url.sha256")), "UTF-8")
      val hash = try source.mkString.trim.split("\\s+").head.toLowerCase(Locale.ROOT) finally source.close()
      Some(Release(version, platform, url, hash))
    } catch {
      case _: NotFound =>
        logger.debug(s"No GitHub release asset for firtool $version on $platform")
        None
    }
  }

  /** Stream the release archive, writing its firtool executable to destination, and verify the checksum. */
  def extract(logger: Logger, release: Release, destination: Path): Unit = {
    val url = release.url
    val windows = release.platform.startsWith("windows-")
    val name = if (windows) "firtool.exe" else "firtool"
    logger.info(s"Downloading firtool ${release.version} for ${release.platform} from $url")
    val digest = MessageDigest.getInstance("SHA-256")
    val download = new DigestInputStream(open(url), digest)
    val archive: ArchiveInputStream =
      if (windows) new ZipArchiveInputStream(download) else new TarArchiveInputStream(new GZIPInputStream(download))
    try {
      logger.debug(s"Extracting firtool from $url")
      Iterator.continually(archive.getNextEntry).takeWhile(_ != null)
        .find(e => !e.isDirectory && (e.getName == s"bin/$name" || e.getName.endsWith(s"/bin/$name"))) match {
        case Some(_) => Files.copy(archive, destination, StandardCopyOption.REPLACE_EXISTING)
        case None    => throw new IOException(s"$url does not contain bin/$name")
      }
      // Drain the rest of the download so the digest covers the whole archive.
      val buffer = new Array[Byte](64 * 1024)
      while (download.read(buffer) != -1) {}
    } finally archive.close()
    val actual = digest.digest().map(b => f"${b & 0xff}%02x").mkString
    if (actual != release.sha256) {
      throw new IOException(s"SHA-256 checksum mismatch for $url: expected ${release.sha256}, actual $actual")
    }
    logger.debug(s"Verified SHA-256 checksum: $actual")
  }
}
