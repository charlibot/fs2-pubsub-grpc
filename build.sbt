ThisBuild / scalaVersion := "2.13.8"

lazy val `fs2-pubsub-grpc` = project
  .enablePlugins(Fs2Grpc)
  .settings(
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion,

      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-3" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-3",

      "com.google.api.grpc" % "proto-google-cloud-pubsub-v1" % "1.98.3" % "protobuf-src" intransitive(),

      "io.grpc" % "grpc-auth" % "1.45.1",
      "com.google.auth" % "google-auth-library-oauth2-http" % "1.6.0",
      "org.typelevel" %% "log4cats-core" % "2.1.1",
    ),
  )

lazy val `pubsub-example` = project
  .dependsOn(`fs2-pubsub-grpc`)
  .settings(
    libraryDependencies ++= {
      Seq(
        "io.grpc" % "grpc-netty" % "1.45.1",
        "org.typelevel" %% "log4cats-slf4j" % "2.1.1",
        "ch.qos.logback" % "logback-classic" % "1.2.6",
      )
    },
  )
