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

  private def withStream[A](url: URL)(read: InputStream => A): A = {
    def open(current: URL, redirects: Int): A = {
      val connection = current.openConnection()
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(60000)
      connection.setRequestProperty("User-Agent", "firtool-resolver")
      val http = connection match {
        case h: HttpURLConnection => Some(h)
        case _ => None
      }
      try {
        http.foreach(_.setInstanceFollowRedirects(false))
        val status = http.map(_.getResponseCode).getOrElse(200)
        if (Set(301, 302, 303, 307, 308).contains(status)) {
          if (redirects >= 10) throw new IOException(s"Too many redirects downloading $url")
          val location = connection.getHeaderField("Location")
          if (location == null) throw new IOException(s"Missing redirect location downloading $current")
          val next = new URL(current, location)
          if (next.getProtocol != "https" && next.getProtocol != "http") {
            throw new IOException(s"Unsupported redirect URL: $next")
          }
          open(next, redirects + 1)
        } else if (status != 200) {
          throw new HttpError(status, current)
        } else {
          val stream = connection.getInputStream
          try read(stream)
          finally stream.close()
        }
      } finally http.foreach(_.disconnect())
    }
    open(url, 0)
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

  /** None means the checksum asset is not published, so a fallback platform may be tried. */
  def fetch(logger: Logger, version: String, platform: String, directory: Path): Option[Path] = {
    val circtPlatform = if (platform == "macos-aarch64") "macos-arm64" else platform
    val extension = if (platform.startsWith("windows-")) "zip" else "tar.gz"
    val asset = s"firrtl-bin-$circtPlatform.$extension"
    val base = sys.props.get("chisel.firtool.url")
      .orElse(sys.env.get("CHISEL_FIRTOOL_URL"))
      .getOrElse("https://github.com/llvm/circt/releases/download")
    val url = new URL(s"${base.stripSuffix("/")}/firtool-$version/$asset")
    val checksumUrl = new URL(s"$url.sha256")
    logger.debug(s"Downloading SHA-256 checksum from $checksumUrl")
    val checksum = try {
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
      case e: HttpError if e.status == 404 => return None
      case _: FileNotFoundException if url.getProtocol == "file" => return None
    }
    logger.debug(s"Expected SHA-256: $checksum")
    logger.info(s"Downloading firtool $version for $platform from $url")
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
      if (actual != checksum) {
        throw new IOException(s"SHA-256 checksum mismatch for $url: expected $checksum, actual $actual")
      }
      logger.debug(s"Verified SHA-256 checksum: $actual")
      keep = true
      Some(archive)
    } finally if (!keep) Files.deleteIfExists(archive)
  }
}
