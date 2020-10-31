package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.query.Queries
import io.github.vigoo.awsquery.report._
import io.github.vigoo.awsquery.report.cache._
import io.github.vigoo.awsquery.report.render.{Rendering, renderAsg, renderEc2Instance, renderElb}
import io.github.vigoo.clipp
import io.github.vigoo.clipp.ParserFailure
import io.github.vigoo.clipp.syntax._
import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.zioapi.config.{ClippConfig, parameters}
import io.github.vigoo.zioaws._
import io.github.vigoo.zioaws.autoscaling.AutoScaling
import io.github.vigoo.zioaws.core.{AwsError, GenericAwsError, aspects}
import io.github.vigoo.zioaws.core.aspects.{AwsCallAspect, Described}
import io.github.vigoo.zioaws.core.config.{AwsConfig, CommonAwsConfig}
import io.github.vigoo.zioaws.ec2.Ec2
import io.github.vigoo.zioaws.elasticbeanstalk.ElasticBeanstalk
import io.github.vigoo.zioaws.elasticloadbalancing.ElasticLoadBalancing
import nl.vroste.rezilience.CircuitBreaker.State
import nl.vroste.rezilience.{CircuitBreaker, Policy, Retry, TrippingStrategy}
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.builder.api.{ConfigurationBuilder, ConfigurationBuilderFactory}
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
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
  // "execution graph dump" aspect for generating diagrams for the post?
  // typesafe pprint monad

  private def throttlingPolicy: ZManaged[Clock with Logging, Nothing, Policy[AwsError]] =
    for {
      logging <- ZManaged.environment[Logging]
      cb <- CircuitBreaker.make[AwsError](
        trippingStrategy = TrippingStrategy.failureCount(1),
        resetPolicy = Retry.Schedules.exponentialBackoff(1.second, 1.minute),
        isFailure = {
          case GenericAwsError(error: AwsServiceException) if error.isThrottlingException => true
        },
        onStateChange = {
          case State.Closed =>
            log.info(s"Circuit breaker closed").provide(logging)
          case State.HalfOpen =>
            log.info(s"Circuit breaker half open").provide(logging)
          case State.Open =>
            log.info(s"Circuit breaker open").provide(logging)
        }
      )
    } yield cb.toPolicy

  private def awsQuery(): ZIO[Clock with Console with Logging with ClippConfig[Parameters], Nothing, ExitCode] =
    throttlingPolicy.use { policy =>
      val throttling = new AwsCallAspect[Any] {
        override def apply[R1, A](f: ZIO[R1, AwsError, aspects.Described[A]]): ZIO[R1, AwsError, aspects.Described[A]] =
          policy(f).mapError {
            case Policy.WrappedError(e) => e
            case Policy.BulkheadRejection => AwsError.fromThrowable(new RuntimeException(s"Bulkhead rejection"))
            case Policy.CircuitBreakerOpen => AwsError.fromThrowable(new RuntimeException(s"AWS rate limit exceeded"))
          }
      }

      val callLogging: AwsCallAspect[Logging] =
        new AwsCallAspect[Logging] {
          override final def apply[R1 <: Logging, A](f: ZIO[R1, AwsError, Described[A]]): ZIO[R1, AwsError, Described[A]] =
            f.flatMap { case r @ Described(_, description) =>
              log.info(s"[${description.service}/${description.operation}]").as(r)
            }
        }

      val commonConfig = ZLayer.succeed(CommonAwsConfig(
        region = Some(Region.US_EAST_1),
        credentialsProvider = DefaultCredentialsProvider.create(),
        endpointOverride = None,
        commonClientConfig = None
      ))

      val awsCore = (netty.default ++ commonConfig) >>> core.config.configured()

      val awsClients =
          ec2.live @@ (throttling >>> callLogging) ++
          elasticloadbalancing.live @@ (throttling >>> callLogging) ++
          elasticbeanstalk.live @@ (throttling >>> callLogging) ++
          autoscaling.live @@ (throttling >>> callLogging)

      val finalLayer =
        ((ZLayer.service[Logger[String]] ++ awsCore) >>> awsClients) ++
          ((Console.any ++ cache.live) >+> render.live)

      runQuery()
        .provideSomeLayer[Clock with Console with Logging with ClippConfig[Parameters]](finalLayer)
        .tapError { error =>
          console.putStrLnErr(error.toString)
        }
        .exitCode
    }

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

    for {
      result <- awsQuery()
        .provideCustomLayer(params >+> logging)
        .catchAll { failure: ParserFailure => ZIO.succeed(ExitCode.failure) }
      _ <- ZIO.effect(LogManager.shutdown()).orDie
    } yield result
  }
}