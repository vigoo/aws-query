package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.query.Queries
import io.github.vigoo.awsquery.report._
import io.github.vigoo.awsquery.report.cache._
import io.github.vigoo.awsquery.report.render.{Rendering, renderEc2Instance}
import io.github.vigoo.zioaws._
import io.github.vigoo.zioaws.core.AwsError
import org.apache.logging.log4j.LogManager
import zio._
import zio.console.Console
import zio.logging._
import zio.logging.slf4j._

object Main extends App {
  private def runQuery(instanceId: String): ZIO[Console with Logging with ReportCache with Rendering with AllServices, AwsError, Unit] =
    for {
      result <- Queries.getInstanceReport(instanceId).run
      _ <- renderEc2Instance(result)
    } yield ()

  // TODOs
  // logging? and rate limiting as aspects
  // unify common code
  // "execution graph dump" aspect for generating diagrams for the post?
  // implicit withFilter to make zipPars nicer?

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val logging = Slf4jLogger.make { (context, message) => message }
    val awsCore = netty.client() >>> core.config.default
    val awsClients =
      ec2.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
      elasticloadbalancing.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
      elasticbeanstalk.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
      autoscaling.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1))
    val finalLayer =
      (awsCore >>> awsClients) ++
      logging ++
      ((Console.any ++ cache.live) >+> render.live)

    for {
      _ <- runQuery(args(1))
        .provideLayer(finalLayer)
        .catchAll { error =>
          console.putStrLnErr(error.toString)
        }
      _ <- ZIO.effect(LogManager.shutdown()).orDie
    } yield ExitCode.success
  }
}