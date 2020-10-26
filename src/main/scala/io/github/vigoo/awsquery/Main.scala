package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.query.Queries
import io.github.vigoo.awsquery.report._
import io.github.vigoo.awsquery.report.cache._
import io.github.vigoo.awsquery.report.render.{Rendering, renderAsg, renderEc2Instance, renderElb}
import io.github.vigoo.zioaws._
import io.github.vigoo.zioaws.core.AwsError
import org.apache.logging.log4j.LogManager
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.logging._
import zio.logging.slf4j._
import zio.query.ZQuery

object Main extends App {
  private def renderQuery[K <: ReportKey, R <: Report](query: ZQuery[Console with Logging with ReportCache with AllServices, AwsError, LinkedReport[K, R]],
                                                       render: LinkedReport[K, R] => ZIO[Rendering, Nothing, Unit]): ZQuery[Console with Logging with ReportCache with AllServices, AwsError, Option[ZIO[Rendering, Nothing, Unit]]] =
    query
      .foldCauseM(_ => ZQuery.none, ZQuery.some(_))
      .map(_.map(render))

  private def runQuery(input: String): ZIO[Console with Logging with ReportCache with Rendering with AllServices, AwsError, Unit] = {
    val possibleQueries =
      List(
        renderQuery[Ec2InstanceKey, Ec2InstanceReport](Queries.getInstanceReport(input), renderEc2Instance),
        renderQuery[ElbKey, ElbReport](Queries.getElbReportByInput(input), renderElb(_, None)),
        renderQuery[AsgKey, AsgReport](Queries.getAsgReportByInput(input), renderAsg),
      )

    for {
      renderers <- ZQuery.collectAllPar(possibleQueries).run
      _ <- ZIO.foreach_(renderers.flatten)(identity)
    } yield ()
  }

  // TODOs
  // rate limiting as zio-aws aspects
  // "execution graph dump" aspect for generating diagrams for the post?
  // typesafe pprint monad
  // shared region config (with zio-config)
  // clipp

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