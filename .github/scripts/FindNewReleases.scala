//> using scala "2.13"
//> using lib "com.47deg::github4s:0.32.1"
//> using lib "io.get-coursier::coursier:2.1.8"
//> using lib "io.get-coursier::coursier-cats-interop:2.1.8"
//> using lib "com.lihaoyi::upickle:3.1.3"
//> using lib "co.fs2::fs2-core:3.9.3"
// using options "-unchecked", "-deprecation", "-feature", "-Xcheckinit", "-Xfatal-warnings", "-Ywarn-dead-code", "-Ywarn-unused"

import cats.effect.{IO, IOApp}
import cats.effect.unsafe.implicits.global
import org.http4s.client.{Client, JavaNetClientBuilder}
import github4s.{GHResponse, Github}
import github4s.domain.{Pagination, Release}
import coursier._
import coursier.cache._
import coursier.interop.cats._
import upickle.default._
import fs2._

import scala.util.control.NonFatal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter._
import cats.effect.ExitCode

object Releases {

  // These are hardcoded but could be turned into parameters

  val httpClient: Client[IO] = JavaNetClientBuilder[IO].create
  val token: Option[String] = sys.env.get("GITHUB_TOKEN")
  val github: Github[IO] = Github[IO](httpClient, token)
  val cache = FileCache[IO]()
  val org = "llvm"
  val repo = "circt"
  val module = mod"org.chipsalliance:llvm-firtool"

  def liftToIO[A](response: GHResponse[A]): IO[A] = response.result match {
    case Left(e) =>
      IO.raiseError(
        new Exception(
          s"Unable to fetch contributors for $org/$repo. Did you misspell it? Did the repository move?" +
            s" Is access token defined: ${token.isDefined}? Original exception: ${e.getMessage}"
        )
      )
    case Right(r) => IO(r)
  }

  def fetchPage(page: Int): IO[List[Release]] =
    for {
      response <- github.repos.listReleases(
        org,
        repo,
        Some(Pagination(page, 40)),
        Map()
      )
      fetched <- liftToIO(response)
    } yield fetched

  // def releases(n: Int = 0): Stream[IO, Release] = {
  //   for {
  //     page <- Stream.eval(fetchPage(n))
  //     next
  //   }
  //   Stream.iterate


  // The listReleases API grabs by page so we need to fetch multiple pages
  // Apparently page 0 and 1 are the same
  def releases: Stream[IO, Release] = {
    Stream
      .unfoldEval[IO, Int, List[Release]](1) { currentPage =>
        // Fetch pages until they are empty
        fetchPage(currentPage).map { page =>
          if (page.isEmpty) None
          else Some((page, currentPage + 1))
        }
      }
      .flatMap(Stream.emits)
  }

  /** Time cutoff for what counts as a "new" release */
  val cutoffForNew: LocalDateTime =
    LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(32)

  /** New releases are those published in the last 48 hours */
  def newReleases: Stream[IO, Release] =
    releases.evalMap { r =>
      val isNew = r.published_at.map { time =>
        val parsed = LocalDateTime.parse(time, ISO_DATE_TIME)
        parsed.isAfter(cutoffForNew)
      }
      val msg = s"Is ${r.tag_name} (${r.published_at}) new? " + isNew.getOrElse(
        "<unpublished>"
      )
      IO.consoleForIO.errorln(msg) *>
        IO(r -> isNew.getOrElse(false))
    }.collectWhile { case (r, true) => r }

  def getVersion(release: Release): String =
    release.tag_name.stripPrefix("firtool-")

  /**  */
  def isPublished(release: Release): Stream[IO, Boolean] = {
    val version = getVersion(release)
    val dep = Dependency(module, version)
    val resolved = Resolve(cache).addDependencies(dep).io
    Stream.eval(resolved.map(_ => true).orElse(IO(false)))
  }

  // def unpublishedReleases: Stream[IO, Release] = newReleases.flatMap(getUnpublished)

  def unpublishedReleases: Stream[IO, Release] =
    for {
      release <- newReleases
      exists <- isPublished(release)
      if !exists
    } yield release
    
  //   val exists =
  //     try {
  //       coursier.Resolve().addDependencies(dep).run()
  //       true
  //     } catch {
  //       case NonFatal(_) => false
  //     }
  //   System.err.println(s"Has $version already been published? $exists")
  //   exists
  // }
}

object Main extends IOApp {

  def run(args: List[String]) = {
    for {
      releases <- Releases.unpublishedReleases.compile.toVector
      // remaining <- releases.map(Releases.getUnpublished).sequence
      _ <- IO.println(s"Resulting releases = " + releases.map(_.tag_name))
    } yield ExitCode.Success
    // .compile.drain.as(ExitCode.Success)
  }
  // val r = Releases.releases
  // Github returns new releases in order so we can takeWhile
  // val newReleases = r.takeWhile(Releases.isNewRelease)
  // val unpublished = newReleases.filter(!Releases.isAlreadyPublished(_))
  // val result = unpublished.map(Releases.getVersion).toList
  // System.err.println("We need to publish version(s): " + result.mkString(", "))

  // val jsonList = write(result)
  // System.out.println(jsonList)
}
