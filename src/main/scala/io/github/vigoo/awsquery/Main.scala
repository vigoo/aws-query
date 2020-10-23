package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.report.{Ec2InstanceKey, Ec2InstanceReport, ElbReport, Report, ReportCache, ReportKey, retrieve}
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticloadbalancing.model.LoadBalancerDescription
import io.github.vigoo.zioaws.elasticbeanstalk.model.EnvironmentDescription
import io.github.vigoo.zioaws.{core, ec2, elasticbeanstalk, elasticloadbalancing, http4s}
import zio._
import zio.logging._
import zio.logging.slf4j._
import zio.query.ZQuery

import scala.util.matching.Regex

object Main extends App {

  import sources._

  type AllServices = ec2.Ec2 with elasticloadbalancing.ElasticLoadBalancing with elasticbeanstalk.ElasticBeanstalk

  val cloudFormationStackRegex: Regex = "^awseb-(.*)-stack$".r

  private def optionally[R, E, A, B](value: Option[A])(f: A => ZQuery[R, E, B]): ZQuery[R, E, Option[B]] =
    value match {
      case Some(value) => f(value).map(Some.apply)
      case None => ZQuery.none
    }

  private def cached[R <: ReportCache, E, A, B <: Report, K <: ReportKey](input: A)(keyFn: A => ZIO[Any, E, K])(query: K => ZQuery[R, E, B]): ZQuery[R, E, B] =
    for {
      cachedResultWithKey <- ZQuery.fromEffect {
        for {
          key <- keyFn(input)
          cacheResult <- retrieve[B](key)
        } yield (cacheResult, key)
      }
      (cachedResult, key) = cachedResultWithKey
      result <- cachedResult match {
        case Some(cachedResult) => ZQuery.succeed(cachedResult)
        case None =>
          for {
            result <- query(key)
            _ <- ZQuery.fromEffect(report.store(key, result))
          } yield result
      }
    } yield result

  private def envBasedQuery(env: EnvironmentDescription.ReadOnly): ZQuery[Logging with AllServices, AwsError, Unit] =
    ???

  private def elbBasedQuery(elb: LoadBalancerDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, ElbReport] = {
    cached(elb)(_.loadBalancerName) { name =>
      for {
        tags <- elbquery.getLoadBalancerTags(name)
        cfStackNameTag = tags.find(_.keyValue == "aws:cloudformation:stack-name")
        ebName <- optionally(cfStackNameTag) { tag =>
          ZQuery.fromEffect(tag.value).map(tagValue => cloudFormationStackRegex.findFirstMatchIn(tagValue).map(_.group(1)))
        }.map(_.flatten)
        env <- optionally(ebName) { name =>
          (ebquery.getEnvironmentById(name) <&> ebquery.getEnvironmentByName(name)).map { case (a, b) => a.orElse(b) }
        }.map(_.flatten)
        envResults <- optionally(env)(envBasedQuery)
      } yield ElbReport()
    }

  private def ec2InstanceQuery(instanceId: ec2.model.primitives.InstanceId): ZQuery[Logging with ReportCache with AllServices, AwsError, Ec2InstanceReport] =
    cached(instanceId)((id: ec2.model.primitives.InstanceId) => ZIO.succeed(Ec2InstanceKey(id))) { _ =>
      for {
        instance <- ec2query.getEc2Instance(instanceId)
        imageId <- ZQuery.fromEffect(instance.imageId)
        imgElb <- (ec2query.getImage(imageId) <&> elbquery.loadBalancerOf(instanceId))
        (image, elb) = imgElb
        elbReport <- optionally(elb)(elbBasedQuery)
      } yield Ec2InstanceReport()
    }

  private def runQuery(instanceId: String): ZIO[Logging with ReportCache with AllServices, AwsError, Unit] =
    for {
      result <- ec2InstanceQuery(instanceId).run
    } yield ()

  // TODOs
  // logging and rate limiting as aspects
  // unify common code
  // "execution graph dump" aspect for generating diagrams for the post?
  // implicit withFilter to make zipPars nicer?
  // cached report queries should return reportkey, typed reportlink between models to cut cycles

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val logging = Slf4jLogger.make { (context, message) =>
      val correlationId = LogAnnotation.CorrelationId.render(
        context.get(LogAnnotation.CorrelationId)
      )
      s"[$correlationId] $message"
    }
    val layer =
      (http4s.client() >>> core.config.default >>>
        (ec2.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
          elasticloadbalancing.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
          elasticbeanstalk.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1))
          )) ++ logging
    for {
      _ <- runQuery(args(1))
        .provideLayer(layer)
        .catchAll { error =>
          console.putStrLnErr(error.toString)
        }
    } yield ExitCode.success
  }
}