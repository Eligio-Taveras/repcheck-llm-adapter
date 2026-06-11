import org.typelevel.scalacoptions.ScalacOption
import sbt.Keys.libraryDependencies
import sbt.Def
import Dependencies.*
import com.repcheck.sbt.ExceptionUniquenessPlugin.autoImport.exceptionUniquenessRootPackages

val isScala212: Def.Initialize[Boolean] = Def.setting {
  VersionNumber(scalaVersion.value).matchesSemVer(SemanticSelector("2.12.x"))
}

ThisBuild / dynverSonatypeSnapshots := true

lazy val commonSettings = Seq(
  organization := "com.repcheck",
  scalaVersion := "3.7.3",
  publishTo := Some(
    "GitHub Packages" at s"https://maven.pkg.github.com/Eligio-Taveras/repcheck-llm-adapter"
  ),
  publishMavenStyle := true,
  credentials ++= {
    val envCreds = for {
      user  <- sys.env.get("GITHUB_ACTOR")
      token <- sys.env.get("GITHUB_TOKEN")
    } yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)

    val fileCreds = {
      val f = Path.userHome / ".sbt" / ".github-packages-credentials"
      if (f.exists) Some(Credentials(f)) else None
    }

    envCreds.orElse(fileCreds).toSeq
  },
  resolvers ++= Seq(
    "GitHub Packages - shared-models" at "https://maven.pkg.github.com/Eligio-Taveras/repcheck-shared-models",
    "GitHub Packages - pipeline-models" at "https://maven.pkg.github.com/Eligio-Taveras/repcheck-pipeline-models",
    "GitHub Packages - repcheck-utils" at "https://maven.pkg.github.com/Eligio-Taveras/repcheck-utils",
  ),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.18" % Test
  ),
  semanticdbEnabled := true,
  tpolecatScalacOptions ++= ScalaCConfig.scalaCOptions,
  tpolecatScalacOptions ++= {
    if (isScala212.value) ScalaCConfig.scalaCOption2_12
    else Set.empty[ScalacOption]
  },

  // WartRemover — enforces FP discipline at compile time
  wartremoverErrors ++= Seq(
    Wart.AsInstanceOf,          // No unsafe casts
    Wart.EitherProjectionPartial, // No .get on Either projections
    Wart.IsInstanceOf,          // No runtime type checks — use pattern matching
    Wart.MutableDataStructures, // No mutable collections
    Wart.Null,                  // No null — use Option
    Wart.OptionPartial,         // No Option.get — use fold/map/getOrElse
    Wart.Return,                // No return statements
    Wart.StringPlusAny,         // No string + any — use interpolation
    Wart.IterableOps,           // No .head/.tail on collections — use headOption
    Wart.TryPartial,            // No Try.get — use fold/recover
    Wart.Var                    // No mutable vars
  ),
  wartremoverWarnings ++= Seq(
    Wart.Throw                  // Warn on bare throw — prefer F.raiseError
  )
)

lazy val root = (project in file("."))
  .aggregate(repcheckllmadapter, docGenerator)
  .settings(
    commonSettings,
    name := "repcheck-llm-adapter-root",
    publish / skip := true
  )

lazy val repcheckllmadapter = (project in file("repcheck-llm-adapter"))
  .enablePlugins(com.repcheck.sbt.ExceptionUniquenessPlugin)
  .settings(
    commonSettings,
    name := "repcheck-llm-adapter",
    libraryDependencies ++= http4sEmber ++ circe ++ pureConfig ++ fs2
      ++ catsEffect ++ testDeps
    ,
    libraryDependencies += "com.h2database" % "h2" % "2.2.224" % Test,
    libraryDependencies += "com.repcheck" %% "repchecksharedmodels" % "0.1.55", // F1 contracts (llm/*, incl. llm/prompt)
    libraryDependencies += "com.repcheck" %% "repcheck-utils" % "0.1.4", // RetryWrapper/ErrorClassifier + DockerRequired tag
    libraryDependencies += "com.anthropic" % "anthropic-java" % "2.18.0", // Claude Messages API (F2c provider)
    // Conformance specs need live infra (Ollama / Anthropic API key); excluded from `sbt test` — see README
    Test / testOptions +=
      Tests.Argument(TestFrameworks.ScalaTest, "-l", "DockerRequired", "-l", "com.repcheck.tags.E2ETest"),
    // Circe semi-auto derivation for large case classes
    scalacOptions += "-Xmax-inlines:64",
    // Suppress Scala 3 ScalaTest-matcher warnings in TEST sources only (mirrors data-ingestion / shared-models)
    Test / scalacOptions += "-Wconf:msg=unused value of type:s",
    Test / scalacOptions += "-Wconf:msg=is not declared infix:s",
    // Coverage gate: every file must be >= 95% statement AND branch coverage or CI fails (mirrors shared-models)
    coverageMinimumStmtPerFile   := 95,
    coverageMinimumBranchPerFile := 95,
    coverageFailOnMinimum         := true,
    exceptionUniquenessRootPackages := Seq("com.repcheck")
  )

lazy val docGenerator = (project in file("doc-generator"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.anthropic" % "anthropic-java" % "2.18.0",
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    ),
    // Exclude WartRemover for this utility project — uses Java SDK patterns
    wartremoverErrors := Seq.empty,
    wartremoverWarnings := Seq.empty,
    // Exclude from coverage — utility project with no unit tests
    coverageEnabled := false
  )
