import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import com.typesafe.tools.mima.core.{Problem, ProblemFilters}
import sbtrelease.Version
import sbtcrossproject.{crossProject, CrossType}

lazy val root = project.in(file(".")).
  aggregate(coreJVM, coreJS).
  settings(commonSettings).
  settings(
    publish := {},
    publishLocal := {},
    PgpKeys.publishSigned := {},
    publishArtifact := false
  ).
  settings(publishingSettings)

lazy val core = crossProject(JVMPlatform, JSPlatform).
  in(file(".")).
  enablePlugins(AutomateHeaderPlugin).
  settings(commonSettings).
  settings(
    name := "ip4s",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "org.scalatest" %%% "scalatest" % "3.0.5" % "test",
      "org.scalacheck" %%% "scalacheck" % "1.13.5" % "test"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "utf-8",
      "-explaintypes",
      "-feature",
      "-unchecked",
      "-Xcheckinit",
      "-Xfatal-warnings",
      "-Xfuture",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-dead-code",
      "-Ywarn-extra-implicit",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused:implicits",
      "-Ywarn-unused:imports",
      "-Ywarn-unused:locals",
      // "-Ywarn-unused:params", disabled until https://github.com/tkawachi/sbt-doctest/issues/102
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates",
      "-Ywarn-value-discard"
    ),
    scalacOptions in (Compile, console) := (scalacOptions in (Compile, console)).value.filter(opt => !(opt.startsWith("-Ywarn-unused") || opt == "-Xfatal-warnings" || opt == "-Xlint")),
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
    scalacOptions in (Compile, doc) ++= {
      val tagOrBranch = {
        if (version.value endsWith "SNAPSHOT") git.gitCurrentBranch.value
        else ("v" + version.value)
      }
      Seq(
        "-implicits",
        "-implicits-show-all",
        "-sourcepath", baseDirectory.value.getCanonicalPath,
        "-doc-source-url", s"https://github.com/comcast/ip4s/tree/$tagOrBranch/€{FILE_PATH}.scala",
        "-diagrams"
      )
    },
    sourceDirectories in (Compile, scalafmt) += baseDirectory.value / "../shared/src/main/scala",
    scalafmtOnCompile := true,
    doctestTestFramework := DoctestTestFramework.ScalaTest,
    initialCommands := "import com.comcast.ip4s._"
  ).
  jvmSettings(
    libraryDependencies += "com.google.guava" % "guava" % "23.6.1-jre" % "test",
    scalacOptions in Tut := (scalacOptions in Compile).value.filter(opt => !(opt.startsWith("-Ywarn-unused") || opt == "-Xfatal-warnings" || opt == "-Xlint")),
    tutTargetDirectory := baseDirectory.value / "../docs",
    OsgiKeys.exportPackage := Seq("com.comcast.ip4s.*;version=${Bundle-Version}"),
    OsgiKeys.importPackage := {
      val Some((major, minor)) = CrossVersion.partialVersion(scalaVersion.value)
      Seq(s"""scala.*;version="[$major.$minor,$major.${minor + 1})"""", "*")
    },
    OsgiKeys.privatePackage := Seq("com.comcast.ip4s.*"),
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package"),
    osgiSettings
  ).
  jvmSettings(mimaSettings).
  settings(publishingSettings)

lazy val coreJVM = core.jvm.enablePlugins(TutPlugin, SbtOsgi)
lazy val coreJS = core.js.disablePlugins(DoctestPlugin)

lazy val commonSettings = Seq(
  organization := "com.comcast",
  organizationName := "Comcast Cable Communications Management, LLC",
  organizationHomepage := Some(new URL("https://comcast.github.io")),
  git.remoteRepo := "git@github.com:comcast/ip4s.git",
  startYear := Some(2018),
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  unmanagedResources in Compile ++= {
    val base = baseDirectory.value
    (base / "NOTICE") +: (base / "LICENSE") +: (base / "CONTRIBUTING") +: ((base / "licenses") * "LICENSE_*").get
  },
  scalaVersion := "2.12.6",
  crossScalaVersions := Seq("2.12.6")
)

lazy val publishingSettings = Seq(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots".at(nexus + "content/repositories/snapshots"))
    else
      Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
  },
  credentials ++= (for {
    username <- Option(System.getenv().get("SONATYPE_USERNAME"))
    password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq,
  publishMavenStyle := true,
  pomIncludeRepository := (_ => false),
  pomExtra := {
    <developers>
      {for ((username, name) <- contributors) yield
      <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>
      }
    </developers>
  },
  pomPostProcess := { node =>
    import scala.xml._
    import scala.xml.transform._
    def stripIf(f: Node => Boolean) = new RewriteRule {
      override def transform(n: Node) = if (f(n)) NodeSeq.Empty else n
    }
    val stripTestScope = stripIf { n =>
      n.label == "dependency" && (n \ "scope").text == "test"
    }
    new RuleTransformer(stripTestScope).transform(node)(0)
  },
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value
)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := previousVersion(version.value).map { pv =>
    organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
  }.toSet,
  mimaBinaryIssueFilters ++= Nil
)

def previousVersion(currentVersion: String): Option[String] = {
  val Version = """(\d+)\.(\d+)\.(\d+).*""".r
  val Version(x, y, z) = currentVersion
  if (z == "0") None
  else Some(s"$x.$y.${z.toInt - 1}")
}

lazy val contributors = Seq(
  "mpilquist" -> "Michael Pilquist",
  "matthughes" -> "Matt Hughes"
)