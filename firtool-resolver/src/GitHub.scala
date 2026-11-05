// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import java.io.{FileNotFoundException, IOException, InputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import scala.io.Source

/** Download CIRCT release assets without adding dependencies to the shaded resolver. */
private[firtoolresolver] object GitHub {
  private class HttpError(val status: Int, url: URL)
      extends IOException(s"HTTP $status downloading $url")

  /** A published release asset for one platform, with its expected SHA-256. */
  final case class Release(version: String, platform: String, url: URL, sha256: String) {
    def windows: Boolean = platform.startsWith("windows-")
  }

  private def withStream[A](url: URL)(read: InputStream => A): A = {
    val connection = url.openConnection()
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(60000)
    connection.setRequestProperty("User-Agent", "firtool-resolver")
    try {
      connection match {
        // HttpURLConnection follows redirects (GitHub redirects to release-assets.githubusercontent.com),
        // but never across protocols, so an https download cannot be downgraded to http.
        case http: HttpURLConnection =>
          val status = http.getResponseCode
          if (status != 200) throw new HttpError(status, http.getURL)
        case _ =>
      }
      val stream = connection.getInputStream
      try read(stream)
      finally stream.close()
    } finally connection match {
      case http: HttpURLConnection => http.disconnect()
      case _ =>
    }
  }

  private def retry[A](logger: Logger, url: URL)(download: => A): A = {
    var retries = 0
    while (true) {
      try return download
      catch {
        case e: IOException =>
          val transient = e match {
            case h: HttpError => h.status >= 500 || h.status == 408 || h.status == 429
            case _: FileNotFoundException => false
            case _ => true
          }
          if (!transient || retries == 2) throw e
          retries += 1
          logger.debug(s"Retrying download of $url ($retries/2): ${e.getMessage}")
          Thread.sleep(250L * retries)
      }
    }
    throw new IllegalStateException("unreachable")
  }

  /** Look up the release asset and its checksum. None means the asset is not published for this platform. */
  def lookup(logger: Logger, version: String, platform: String): Option[Release] = {
    val circtPlatform = if (platform == "macos-aarch64") "macos-arm64" else platform
    val extension = if (platform.startsWith("windows-")) "zip" else "tar.gz"
    val base = sys.props.get("chisel.firtool.url")
      .orElse(sys.env.get("CHISEL_FIRTOOL_URL"))
      .getOrElse("https://github.com/llvm/circt/releases/download")
    val url = new URL(s"${base.stripSuffix("/")}/firtool-$version/firrtl-bin-$circtPlatform.$extension")
    val checksumUrl = new URL(s"$url.sha256")
    logger.debug(s"Downloading SHA-256 checksum from $checksumUrl")
    val checksum = try Some {
      retry(logger, checksumUrl) {
        withStream(checksumUrl) { stream =>
          val source = Source.fromInputStream(stream, "UTF-8")
          // Checksum files are tiny; cap their size even for a misconfigured mirror.
          val text = try source.take(4097).mkString finally source.close()
          if (text.length > 4096) throw new IllegalArgumentException(s"Checksum file too large: $checksumUrl")
          val hash = text.trim.split("\\s+").head
          if (!hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(s"Invalid SHA-256 checksum from $checksumUrl: $text")
          }
          hash.toLowerCase(java.util.Locale.ROOT)
        }
      }
    } catch {
      case e: HttpError if e.status == 404 => None
      case _: FileNotFoundException if url.getProtocol == "file" => None
    }
    checksum match {
      case Some(hash) =>
        logger.debug(s"Expected SHA-256: $hash")
        Some(Release(version, platform, url, hash))
      case None =>
        logger.debug(s"No GitHub release asset for firtool $version on $platform")
        None
    }
  }

  /** Download the release archive into directory and verify its checksum. */
  def download(logger: Logger, release: Release, directory: Path): Path = {
    val url = release.url
    logger.info(s"Downloading firtool ${release.version} for ${release.platform} from $url")
    val extension = if (release.windows) "zip" else "tar.gz"
    val archive = Files.createTempFile(directory, "firtool-archive-", s".$extension")
    var keep = false
    try {
      val actual = retry(logger, url) {
        val digest = MessageDigest.getInstance("SHA-256")
        withStream(url) { stream =>
          val input = new DigestInputStream(stream, digest)
          val output = Files.newOutputStream(archive)
          try {
            val buffer = new Array[Byte](64 * 1024)
            var count = input.read(buffer)
            while (count != -1) {
              output.write(buffer, 0, count)
              count = input.read(buffer)
            }
          } finally output.close()
        }
        digest.digest().map(b => f"${b & 0xff}%02x").mkString
      }
      if (actual != release.sha256) {
        throw new IOException(s"SHA-256 checksum mismatch for $url: expected ${release.sha256}, actual $actual")
      }
      logger.debug(s"Verified SHA-256 checksum: $actual")
      keep = true
      archive
    } finally if (!keep) Files.deleteIfExists(archive)
  }
}
