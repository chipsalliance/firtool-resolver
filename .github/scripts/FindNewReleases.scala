//> using scala "2.13"
//> using dep "com.47deg::github4s:0.32.1"
//> using dep "io.get-coursier::coursier:2.1.10"
//> using dep "com.lihaoyi::upickle:4.0.1"
//> using dep "com.lihaoyi::requests:0.9.0"
//> using options "-unchecked", "-deprecation", "-feature", "-Xcheckinit", "-Xfatal-warnings", "-Ywarn-dead-code", "-Ywarn-unused"
//> using packaging.graalvmArgs "--enable-url-protocols=https"

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.client.{Client, JavaNetClientBuilder}
import github4s.{GHResponse, Github}
import github4s.domain.{Pagination, Release}
import coursier._
import upickle.default._

import scala.util.control.NonFatal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter._

object Releases {

  // These are hardcoded but could be turned into parameters

  // NOTE: These files must be kept in sync with the artifacts downloaded in build.sc
  val expectedAritfacts = Seq(
    "firrtl-bin-linux-x64.tar.gz", "firrtl-bin-macos-x64.tar.gz", "firrtl-bin-windows-x64.zip"
  )

  val httpClient: Client[IO] = JavaNetClientBuilder[IO].create
  val token: Option[String] = sys.env.get("GITHUB_TOKEN")
  val github: Github[IO] = Github[IO](httpClient, token)
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

  // The listReleases API grabs by page so we need to fetch multiple pages
  // Apparently page 0 and 1 are the same
  def releases: LazyList[Release] = {
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
    def fetch(page: Int = 1): LazyList[Release] = {
      val fetched = fetchPage(page).unsafeRunSync()
      if (fetched.isEmpty) LazyList.empty
      else fetched.to(LazyList) #::: fetch(page + 1)
    }
    fetch()
  }

  /** Time cutoff for what counts as a "new" release */
  val cutoffForNew: LocalDateTime =
    LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(7)

  def isFirtoolRelease(release: Release): Boolean = {
    val isFirtool = release.tag_name.startsWith("firtool")
    System.err.println(s"Is ${release.tag_name} a firtool release? $isFirtool")
    isFirtool
  }

  /** New releases are those published in the last 48 hours */
  def isNewRelease(release: Release): Boolean = {
    val isNew = release.published_at.map { time =>
      val parsed = LocalDateTime.parse(time, ISO_DATE_TIME)
      parsed.isAfter(cutoffForNew)
    }
    System.err.println(
      s"Is ${release.tag_name} (${release.published_at}) new? " + isNew
        .getOrElse("<unpublished>")
    )
    isNew.getOrElse(false)
  }

  def getVersion(release: Release): String =
    release.tag_name.stripPrefix("firtool-")

  /** Check if a release has already been published */
  def isAlreadyPublished(release: Release): Boolean = {
    val version = getVersion(release)
    val dep = Dependency(module, version)
    val exists =
      try {
        coursier.Resolve().addDependencies(dep).run()
        true
      } catch {
        case NonFatal(_) => false
      }
    System.err.println(s"Has $version already been published? $exists")
    exists
  }

  /** Check if the artifacts needed to publish llvm-firtool have been uploaded */
  def necessaryArtifactsExist(release: Release): Boolean = {
    // Annoyingly, Github4s does not return information about artifacts in the Release object
    // So we need to do another request
    val headers = token.map(t => Seq("authorization" -> s"Bearer ${t}")).getOrElse(Seq.empty)
    val response = requests.get(release.assets_url, headers = headers)

    val releaseArtifacts = ujson.read(response.data.array).arr
    val releaseArtifactNames = releaseArtifacts.map(_.obj("name").str).toSet

    System.err.println(s"For ${release.tag_name}:")
    val missingArtifacts = expectedAritfacts.filterNot { art =>
      val found = releaseArtifactNames.contains(art)
      System.err.println(s"  $art found? $found")
      found
    }
    missingArtifacts.isEmpty
  }
}

object Main extends App {

  val allCirctReleases = Releases.releases
  val firtoolReleases = allCirctReleases.filter(Releases.isFirtoolRelease)
  // Github returns new releases in order so we can takeWhile
  val newReleases = firtoolReleases.takeWhile(Releases.isNewRelease)
  val unpublished = newReleases.filter(!Releases.isAlreadyPublished(_))
  val ready = unpublished.filter(Releases.necessaryArtifactsExist)
  val result = ready.map(Releases.getVersion).toList
  System.err.println("We need to publish version(s): " + result.mkString(", "))

  val jsonList = write(result)
  System.out.println(jsonList)
}
