package io.github.vigoo.awsquery.sources

import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticbeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.ElasticBeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.model._
import zio.{Chunk, ZIO}
import zio.logging.{Logging, log}
import zio.query.{CompletedRequestMap, DataSource, Request, ZQuery}

object ebquery {
  sealed trait EbEnvRequest extends Request[AwsError, Option[EnvironmentDescription.ReadOnly]]
  case class GetEnvironmentByName(name: primitives.EnvironmentName) extends EbEnvRequest
  case class GetEnvironmentById(id: primitives.EnvironmentId) extends EbEnvRequest

  val ebEnvDataSource: DataSource[Logging with ElasticBeanstalk, EbEnvRequest] = DataSource.Batched.make("eb-env") { (requests: Chunk[EbEnvRequest]) =>
    val byName = requests.collect { case GetEnvironmentByName(name) => name }
    val byId = requests.collect { case GetEnvironmentById(id) => id }

    val byNameResultMap =
      for {
        _ <- log.info(s"DescribeEnvironmentRequest (${byName.mkString(", ")}")
        initialResultMap = byName.foldLeft(CompletedRequestMap.empty) { (resultMap, name) => resultMap.insert(GetEnvironmentByName(name))(Right(None)) }
        resultMap <- elasticbeanstalk
          .describeEnvironments(DescribeEnvironmentsRequest(environmentNames = Some(byName)))
          .foldM(initialResultMap) { (resultMap, item) =>
            for {
              name <- item.environmentName
              id <- item.environmentId
            } yield resultMap
              .insert(GetEnvironmentById(id))(Right(Some(item)))
              .insert(GetEnvironmentByName(name))(Right(Some(item)))
          }
          .catchAll { error =>
            ZIO.succeed(
              byName.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
                resultMap.insert(GetEnvironmentByName(req))(Left(error))
              }
            )
          }
      } yield resultMap

    val byIdResultMap =
      for {
        _ <- log.info(s"DescribeEnvironmentRequest (${byId.mkString(", ")}")
        initialResultMap = byId.foldLeft(CompletedRequestMap.empty) { (resultMap, id) => resultMap.insert(GetEnvironmentById(id))(Right(None)) }
        resultMap <- elasticbeanstalk
          .describeEnvironments(DescribeEnvironmentsRequest(environmentIds = Some(byId)))
          .foldM(initialResultMap) { (resultMap, item) =>
            for {
              name <- item.environmentName
              id <- item.environmentId
            } yield resultMap
              .insert(GetEnvironmentById(id))(Right(Some(item)))
              .insert(GetEnvironmentByName(name))(Right(Some(item)))
          }
          .catchAll { error =>
            ZIO.succeed(
              byName.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
                resultMap.insert(GetEnvironmentById(req))(Left(error))
              }
            )
          }
      } yield resultMap

    byNameResultMap.zipWithPar(byIdResultMap)(_ ++ _)
  }

  def getEnvironmentById(id: primitives.EnvironmentId): ZQuery[Logging with ElasticBeanstalk, AwsError, Option[EnvironmentDescription.ReadOnly]] =
    ZQuery.fromRequest(GetEnvironmentById(id))(ebEnvDataSource)

  def getEnvironmentByName(name: primitives.EnvironmentName): ZQuery[Logging with ElasticBeanstalk, AwsError, Option[EnvironmentDescription.ReadOnly]] =
    ZQuery.fromRequest(GetEnvironmentByName(name))(ebEnvDataSource)


  case class GetEnvironmentResource(id: primitives.EnvironmentId) extends Request[AwsError, EnvironmentResourceDescription.ReadOnly]

  val ebEnvResourcesDataSource: DataSource[Logging with ElasticBeanstalk, GetEnvironmentResource] = DataSource.Batched.make("eb-resource") { (requests: Chunk[GetEnvironmentResource]) =>
    // no batching possible
    ZIO.foldLeft(requests)(CompletedRequestMap.empty) { (resultMap, request) =>
      (for {
        response <- elasticbeanstalk.describeEnvironmentResources(DescribeEnvironmentResourcesRequest(
          environmentId = Some(request.id)
        ))
        resource <- response.environmentResources
      } yield resultMap.insert(request)(Right(resource))).catchAll { error =>
        ZIO.succeed(resultMap.insert(request)(Left(error)))
      }
    }
  }

  def getEnvironmentResource(id: primitives.EnvironmentId): ZQuery[Logging with ElasticBeanstalk, AwsError, EnvironmentResourceDescription.ReadOnly] =
    ZQuery.fromRequest(GetEnvironmentResource(id))(ebEnvResourcesDataSource)
}