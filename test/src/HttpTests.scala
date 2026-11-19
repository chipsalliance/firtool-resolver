// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import Check._
import TestFixtures._

/** Exercise the HTTP code paths (status codes, redirects) which `file:` mirrors cannot reach. */
class HttpTests extends TemporaryDirectorySuite with LogAssertions {

  /** Serves the sandbox's mirror over HTTP.
    *
    * @param handler decides the response for a request path before the file is served; return true if handled
    */
  private def withServer[A](s: Sandbox)(handler: (HttpExchange, String) => Boolean)(body: (String, () => Seq[String]) => A): A = {
    val server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0), 0)
    val requests = new java.util.concurrent.CopyOnWriteArrayList[String]
    server.createContext("/", { exchange: HttpExchange =>
      val path = exchange.getRequestURI.getPath
      requests.add(path)
      try {
        if (!handler(exchange, path)) {
          // Redirect targets live under /redirected/ but serve the same files.
          val file = s.base.resolve(path.stripPrefix("/").stripPrefix("redirected/"))
          if (Files.isRegularFile(file)) {
            val data = Files.readAllBytes(file)
            exchange.sendResponseHeaders(200, data.length.toLong)
            exchange.getResponseBody.write(data)
          } else exchange.sendResponseHeaders(404, -1)
        }
      } finally exchange.close()
    })
    server.start()
    try {
      val url = s"http://${server.getAddress.getAddress.getHostAddress}:${server.getAddress.getPort}"
      import scala.collection.JavaConverters._
      body(url, () => requests.asScala.toVector)
    } finally server.stop(0)
  }

  private def launch(s: Sandbox, url: String, arg: String = null) =
    s.launch(arg = Option(arg).getOrElse(s.version), envVersion = None, envURL = Some(url)).await()

  directory.test("downloads over HTTP") { root =>
    val s = new Sandbox(root)
    withServer(s)((_, _) => false) { (url, requests) =>
      val result = launch(s, url)
      assertEquals(result.exit, 0, result.output)
      assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
      assert(requests().exists(_.endsWith(".sha256")), requests().toString)
      assert(requests().exists(r => r.endsWith(".tar.gz") || r.endsWith(".zip")), requests().toString)
    }
  }

  directory.test("follows HTTP redirects") { root =>
    val s = new Sandbox(root)
    // Like github.com redirecting to release-assets.githubusercontent.com: the first hop only redirects.
    withServer(s) { (exchange, path) =>
      if (!path.startsWith("/redirected/")) {
        exchange.getResponseHeaders.add("Location", s"/redirected$path")
        exchange.sendResponseHeaders(302, -1)
        true
      } else false
    } { (url, requests) =>
      val result = launch(s, url)
      assertEquals(result.exit, 0, result.output)
      assertEquals(Files.readAllBytes(s.binary()).toSeq, s.executable.toSeq)
      assert(requests().exists(_.startsWith("/redirected/")), requests().toString)
    }
  }

  directory.test("a missing release is a clean 404 and not an unexpected failure") { root =>
    val s = new Sandbox(root, platforms = Nil)
    withServer(s)((_, _) => false) { (url, _) =>
      val result = launch(s, url)
      assertEquals(result.exit, 1, result.output)
      checkLog(result.err,
        has(s"No GitHub release asset for firtool ${s.version} on ${Host.platform}"),
        has("Failed to fetch firtool:"))
    }
  }

  directory.test("a server error is reported as an HTTP failure and not a missing release") { root =>
    val s = new Sandbox(root)
    withServer(s) { (exchange, _) => exchange.sendResponseHeaders(500, -1); true } { (url, _) =>
      val result = launch(s, url)
      assertEquals(result.exit, 1, result.output)
      assert(result.err.contains("HTTP 500 downloading"), result.output)
      assertAbsent(result.err, "No GitHub release asset")
      assert(!Files.exists(s.binary()))
    }
  }

  directory.test("a download that fails after the checksum was found leaves no partial install") { root =>
    val s = new Sandbox(root)
    withServer(s) { (exchange, path) =>
      if (path.endsWith(".tar.gz") || path.endsWith(".zip")) { exchange.sendResponseHeaders(500, -1); true }
      else false
    } { (url, _) =>
      val result = launch(s, url)
      assertEquals(result.exit, 1, result.output)
      assert(!Files.exists(s.binary()))
      assert(!Files.exists(s.cache.resolve(s.version)) ||
        !Files.list(s.cache.resolve(s.version).resolve("bin")).iterator().hasNext)
    }
  }
}
