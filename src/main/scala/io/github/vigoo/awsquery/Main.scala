package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.query.Common.{AllServices, QueryEnv}
import io.github.vigoo.awsquery.query.Queries
import io.github.vigoo.awsquery.report._
import io.github.vigoo.awsquery.report.cache._
import io.github.vigoo.awsquery.report.render._
import io.github.vigoo.clipp
import io.github.vigoo.clipp.ParserFailure
import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.syntax._
import io.github.vigoo.clipp.zioapi.config.{ClippConfig, parameters}
import io.github.vigoo.zioaws._
import io.github.vigoo.zioaws.core.aspects.{AwsCallAspect, Described}
import io.github.vigoo.zioaws.core.config.CommonAwsConfig
import io.github.vigoo.zioaws.core.{AwsError, GenericAwsError, aspects}
import nl.vroste.rezilience.CircuitBreaker.State
import nl.vroste.rezilience.Policy.PolicyError
import nl.vroste.rezilience.{CircuitBreaker, Policy, Retry, TrippingStrategy}
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.apache.logging.log4j.{Level, LogManager}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.regions.Region
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
import zio.logging._
import zio.logging.slf4j._
import zio.query.ZQuery
import zio.random.Random

object Main extends App {

  final case class Parameters(verbose: Boolean,
                              searchInput: String,
                              region: String)

  /**
   * Runs a query and renders its result if succeded, ignore failures
   */
  private def renderQuery[K <: ReportKey, R <: Report](query: ZQuery[QueryEnv, AwsError, LinkedReport[K, R]],
                                                       render: LinkedReport[K, R] => ZIO[Rendering, Nothing, Unit]): ZQuery[QueryEnv, AwsError, Option[ZIO[Rendering, Nothing, Unit]]] =
    query
      .foldCauseM(_ => ZQuery.none, ZQuery.some(_)) // don't care about failures, we just want to report successful matches
      .map(_.map(render))

  /**
   * Tries all existing queries on the provided user input and prints gathered reports to the console
   */
  private def runQuery(): ZIO[ClippConfig[Parameters] with Console with Logging with ReportCache with Rendering with AllServices, AwsError, Unit] = {
    parameters[Parameters].flatMap { params =>
      val input = params.searchInput

      val possibleQueries =
        List(
          renderQuery[Ec2InstanceKey, Ec2InstanceReport](Queries.getInstanceReport(input), renderEc2Instance),
          renderQuery[ElbKey, ElbReport](Queries.getElbReportByInput(input), renderElb(_, None)),
          renderQuery[EbAppKey, EbAppReport](Queries.getEbAppReportByInput(input), renderEbApp),
          renderQuery[EbEnvKey, EbEnvReport](Queries.getEbEnvReportByInput(input), renderEbEnv),
          renderQuery[AsgKey, AsgReport](Queries.getAsgReportByInput(input), renderAsg),
        )

      for {
        renderers <- ZQuery.collectAllPar(possibleQueries).run
        _ <- ZIO.foreach_(renderers.flatten)(identity)
      } yield ()
    }
  }

  /**
   * Throttling policy for AWS calls
   */
  private def throttlingPolicy: ZManaged[Random with Clock with Logging, Nothing, Policy[AwsError]] =
    for {
      logging <- ZManaged.environment[Logging]
      cb <- CircuitBreaker.make[AwsError](
        trippingStrategy = TrippingStrategy.failureCount(1),
        resetPolicy = Retry.Schedules.exponentialBackoff(min = 1.second, max = 1.minute),
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
      retry <- Retry.make(min = 1.second, max = 1.minute)
      retryComposable = retry.widen[PolicyError[AwsError]] { case Policy.WrappedError(e) => e }
    } yield cb.toPolicy compose retryComposable.toPolicy

  private def awsQuery(): ZIO[Random with Clock with Console with Logging with ClippConfig[Parameters], Nothing, ExitCode] =
    throttlingPolicy.use { policy =>
      parameters[Parameters].flatMap { params =>
        // Defning zio-aws aspects
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
              f.flatMap { case r@Described(_, description) =>
                log.info(s"[${description.service}/${description.operation}]").as(r)
              }
          }

        // Setting up the layers
        val commonConfig = ZLayer.succeed(CommonAwsConfig(
          region = Some(Region.of(params.region)),
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
    }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val paramSpec = for {
      _ <- metadata("aws-query", "search for AWS infrastructure resources")
      verbose <- flag("Verbose logging", 'v', "verbose")
      searchInput <- parameter[String]("Search input", "NAME_OR_ID")
      region <- optional {
        namedParameter[String]("AWS region", "REGION", "region")
      }
    } yield Parameters(verbose, searchInput, region.getOrElse("us-east-1"))

    val params = clipp.zioapi.config.fromArgsWithUsageInfo(args, paramSpec)
    val logging = log4j2Configuration >+> Slf4jLogger.make { (_, message) => message }

    for {
      result <- awsQuery()
        .provideCustomLayer(params >+> logging)
        .catchAll { _ => ZIO.succeed(ExitCode.failure) }
      _ <- ZIO.effect(LogManager.shutdown()).orDie
    } yield result
  }

  case class Log4jConfiguration()

  private def log4j2Configuration: ZLayer[Has[ClippConfig.Service[Parameters]], Throwable, Has[Log4jConfiguration]] = {
    ZLayer.fromServiceM[ClippConfig.Service[Parameters], Any, Throwable, Log4jConfiguration] { params =>
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
        Log4jConfiguration()
      }
    }
  }
}