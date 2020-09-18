name := "aws-query"
version := "0.1"
scalaVersion := "2.13.3"
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.1",
  "dev.zio" %% "zio-streams" % "1.0.1",
  "dev.zio" %% "zio-query" % "0.2.5",
  "dev.zio" %% "zio-logging-slf4j" % "0.5.0",
  "io.github.vigoo" %% "zio-aws-ec2" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-elasticloadbalancing" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-http4s" % "2.14.7.0",
  "org.apache.logging.log4j" % "log4j-core" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3",
  "com.lmax" % "disruptor" % "3.4.2",
)
