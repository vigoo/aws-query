scalaVersion := "2.13.3"
name := "aws-query"
version := "0.1"
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.3",
  "dev.zio" %% "zio-streams" % "1.0.3",
  "dev.zio" %% "zio-query" % "0.2.5+12-c41557f7-SNAPSHOT",
  "dev.zio" %% "zio-logging-slf4j" % "0.5.0",

  "io.github.vigoo" %% "zio-aws-autoscaling" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-cloudwatch" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-cloudfront" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-ec2" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-elasticloadbalancing" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-elasticbeanstalk" % "2.14.7.0",
  "io.github.vigoo" %% "zio-aws-route53" % "2.14.7.0",

  "io.github.vigoo" %% "zio-aws-netty" % "2.14.7.0",
  "org.apache.logging.log4j" % "log4j-core" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3",
  "com.lmax" % "disruptor" % "3.4.2",
)