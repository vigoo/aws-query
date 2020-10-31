scalaVersion := "2.13.3"
name := "aws-query"
version := "0.1"
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += Resolver.jcenterRepo

val zioAwsVersion = "3.15.16.7"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.3",
  "dev.zio" %% "zio-streams" % "1.0.3",
  "dev.zio" %% "zio-query" % "0.2.5+12-c41557f7-SNAPSHOT",
  "dev.zio" %% "zio-logging-slf4j" % "0.5.0",

  "io.github.vigoo" %% "zio-aws-autoscaling" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-cloudwatch" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-cloudfront" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-ec2" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-elasticloadbalancing" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-elasticbeanstalk" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-route53" % zioAwsVersion,

  "io.github.vigoo" %% "zio-aws-netty" % zioAwsVersion,

  "nl.vroste" %% "rezilience" % "0.5.0",

  "io.github.vigoo" %% "clipp-zio" % "0.4.0",

  "org.apache.logging.log4j" % "log4j-core" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3",
  "com.lmax" % "disruptor" % "3.4.2",
)

scalacOptions += "-deprecation"