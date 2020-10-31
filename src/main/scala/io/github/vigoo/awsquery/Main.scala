package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.query.Queries
import io.github.vigoo.awsquery.report._
import io.github.vigoo.awsquery.report.cache._
import io.github.vigoo.awsquery.report.render.{Rendering, renderAsg, renderEc2Instance, renderElb}
import io.github.vigoo.clipp
import io.github.vigoo.clipp.syntax._
import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.zioapi.config.{ClippConfig, parameters}
import io.github.vigoo.zioaws._
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.core.config.CommonAwsConfig
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.builder.api.{ConfigurationBuilder, ConfigurationBuilderFactory}
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.logging._
import zio.logging.slf4j._
import zio.query.ZQuery

object Main extends App {
  case class Parameters(verbose: Boolean,
                        searchInput: String)

  private def renderQuery[K <: ReportKey, R <: Report](query: ZQuery[Console with Logging with ReportCache with AllServices, AwsError, LinkedReport[K, R]],
                                                       render: LinkedReport[K, R] => ZIO[Rendering, Nothing, Unit]): ZQuery[Console with Logging with ReportCache with AllServices, AwsError, Option[ZIO[Rendering, Nothing, Unit]]] =
    query
      .foldCauseM(_ => ZQuery.none, ZQuery.some(_)) // don't care bout failures, we just want to report successful matches
      .map(_.map(render))

  private def runQuery(): ZIO[ClippConfig[Parameters] with Console with Logging with ReportCache with Rendering with AllServices, AwsError, Unit] = {
    parameters[Parameters].flatMap { params =>
      val input = params.searchInput

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
  }

  // TODOs
  // rate limiting as zio-aws aspects
  // "execution graph dump" aspect for generating diagrams for the post?
  // typesafe pprint monad

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val paramSpec = for {
      _ <- metadata("aws-query", "search for AWS infrastructure resources")
      verbose <- flag("Verbose logging", 'v', "verbose")
      searchInput <- parameter[String]("Search input", "NAME_OR_ID")
    } yield Parameters(verbose, searchInput)
    val params = clipp.zioapi.config.fromArgsWithUsageInfo(args, paramSpec)

    val log4j2 = ZLayer.fromService[ClippConfig.Service[Parameters], Unit] { params =>
      ZIO.effect {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()

        val console = builder.newAppender("CONSOLE", "Console")
        console.addAttribute("target", "SYSTEM_ERR")
        val layout = builder.newLayout("PatternLayout")
        layout.addAttribute("pattern", "%-5p [%logger]: %m%n")
        console.add(layout)
        builder.add(console)

        val level = if (params.parameters.verbose) {
          Level.INFO
        } else {
          Level.WARN
        }
        val rootLogger = builder.newRootLogger(level)
        rootLogger.add(builder.newAppenderRef("CONSOLE"))
        builder.add(rootLogger)
        Configurator.initialize(builder.build())
      }
    }

    val logging = log4j2 >>> Slf4jLogger.make { (_, message) => message }

    val commonConfig = ZLayer.succeed(CommonAwsConfig(
      region = Some(Region.US_EAST_1),
      credentialsProvider = DefaultCredentialsProvider.create(),
      endpointOverride = None,
      commonClientConfig = None
    ))

    val awsCore = (netty.default ++ commonConfig) >>> core.config.configured()

    val awsClients =
        ec2.live ++
        elasticloadbalancing.live ++
        elasticbeanstalk.live ++
        autoscaling.live

    val finalLayer =
      (awsCore >>> awsClients) ++
        (params >+> logging) ++
        ((Console.any ++ cache.live) >+> render.live)

    for {
      _ <- runQuery()
        .provideLayer(finalLayer)
        .catchAll { error =>
          console.putStrLnErr(error.toString)
        }
      _ <- ZIO.effect(LogManager.shutdown()).orDie
    } yield ExitCode.success
  }
}