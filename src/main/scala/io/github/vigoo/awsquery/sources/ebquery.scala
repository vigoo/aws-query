package io.github.vigoo.awsquery.sources

import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticbeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.ElasticBeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.model._
import zio.{Chunk, ZIO}
import zio.logging.{Logging, log}
import zio.query.{CompletedRequestMap, DataSource, Request, ZQuery}

object ebquery {

  sealed trait EbEnvRequest[+A] extends Request[AwsError, A]

  case class GetEnvironmentByName(name: primitives.EnvironmentName) extends EbEnvRequest[Option[EnvironmentDescription.ReadOnly]]

  case class GetEnvironmentById(id: primitives.EnvironmentId) extends EbEnvRequest[Option[EnvironmentDescription.ReadOnly]]

  case class GetEnvironmentByApplicationName(name: primitives.ApplicationName) extends EbEnvRequest[List[EnvironmentDescription.ReadOnly]]

  val ebEnvDataSource: DataSource[Logging with ElasticBeanstalk, EbEnvRequest[Any]] = DataSource.Batched.make("eb-env") { (requests: Chunk[EbEnvRequest[Any]]) =>
    val byName = requests.collect { case GetEnvironmentByName(name) => name }
    val byId = requests.collect { case GetEnvironmentById(id) => id }
    val byAppName = requests.collect { case GetEnvironmentByApplicationName(name) => name }

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

    val byAppNameResultMap = {
      ZIO.foldLeft(byAppName)(CompletedRequestMap.empty) { (previousResultMap, appName) =>
        (for {
          _ <- log.info(s"DescribeEnvironmentsRequest (appName=$appName)")
          foldResult <- elasticbeanstalk
            .describeEnvironments(DescribeEnvironmentsRequest(applicationName = Some(appName)))
            .foldM((previousResultMap, List.empty[EnvironmentDescription.ReadOnly])) { case ((resultMap, items), item) =>
              for {
                name <- item.environmentName
                id <- item.environmentId
              } yield (
                resultMap
                  .insert(GetEnvironmentById(id))(Right(Some(item)))
                  .insert(GetEnvironmentByName(name))(Right(Some(item))),
                item :: items
              )
            }
          (resultMap, items) = foldResult
        } yield resultMap.insert(GetEnvironmentByApplicationName(appName))(Right(items))).catchAll { error =>
          ZIO.succeed(
            byName.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
              resultMap.insert(GetEnvironmentByApplicationName(appName))(Left(error))
            }
          )
        }
      }
    }

    byNameResultMap
      .zipWithPar(byIdResultMap)(_ ++ _)
      .zipWithPar(byAppNameResultMap)(_ ++ _)
  }

  def getEnvironmentById(id: primitives.EnvironmentId): ZQuery[Logging with ElasticBeanstalk, AwsError, Option[EnvironmentDescription.ReadOnly]] =
    ZQuery.fromRequest(GetEnvironmentById(id))(ebEnvDataSource)

  def getEnvironmentByName(name: primitives.EnvironmentName): ZQuery[Logging with ElasticBeanstalk, AwsError, Option[EnvironmentDescription.ReadOnly]] =
    ZQuery.fromRequest(GetEnvironmentByName(name))(ebEnvDataSource)

  def getEnvironmentsByAppName(name: primitives.ApplicationName): ZQuery[Logging with ElasticBeanstalk, AwsError, List[EnvironmentDescription.ReadOnly]] =
    ZQuery.fromRequest(GetEnvironmentByApplicationName(name))(ebEnvDataSource)


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

  case class GetApplicationByName(name: primitives.ApplicationName) extends Request[AwsError, Option[ApplicationDescription.ReadOnly]]

  val ebAppDataSource: DataSource[Logging with ElasticBeanstalk, GetApplicationByName] = DataSource.Batched.make("eb-app") { (requests: Chunk[GetApplicationByName]) =>
    (for {
      _ <- log.info(s"DescribeApplications (${requests.map(_.name).mkString(", ")}")
      initialResultMap = requests.foldLeft(CompletedRequestMap.empty) { (resultMap, req) => resultMap.insert(req)(Right(None)) }
      response <- elasticbeanstalk.describeApplications(DescribeApplicationsRequest(applicationNames = Some(requests.map(_.name))))
      applications <- response.applications
      resultMap <- ZIO.foldLeft(applications)(initialResultMap) { (resultMap, item) =>
        for {
          name <- item.applicationName
        } yield resultMap.insert(GetApplicationByName(name))(Right(Some(item)))
      }
    } yield resultMap).catchAll { error =>
      ZIO.succeed(
        requests.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
          resultMap.insert(req)(Left(error))
        }
      )
    }
  }

  def getApplicationByName(name: primitives.ApplicationName): ZQuery[Logging with ElasticBeanstalk, AwsError, Option[ApplicationDescription.ReadOnly]] =
    ZQuery.fromRequest(GetApplicationByName(name))(ebAppDataSource)
}