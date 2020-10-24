package io.github.vigoo.awsquery.sources

import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.ec2
import io.github.vigoo.zioaws.ec2.model.{DescribeImagesRequest, DescribeInstancesRequest, Image, Instance, primitives}
import zio.logging.LogAnnotation.Name
import zio.{Chunk, ZIO}
import zio.logging.{Logging, log}
import zio.query.{CompletedRequestMap, DataSource, Request, ZQuery}
import zio.stream.ZStream

object ec2query {
  case class GetEc2Instance(id: primitives.InstanceId) extends Request[AwsError, Instance.ReadOnly]

  val ec2InstancesDataSource: DataSource[Logging with ec2.Ec2, GetEc2Instance] = DataSource.Batched.make("ec2") { (requests: Chunk[GetEc2Instance]) =>
    log.locally(Name("EC2" :: Nil)) {
      for {
        _ <- log.info(s"DescribeInstances (${requests.map(_.id).mkString(", ")})")
        result <- ec2.describeInstances(DescribeInstancesRequest(instanceIds = Some(requests.map(_.id))))
          .mapM(_.instances)
          .flatMap(instances => ZStream.fromIterable(instances))
          .foldM(CompletedRequestMap.empty) { (resultMap, item) =>
            for {
              instanceId <- item.instanceId
            } yield resultMap.insert(GetEc2Instance(instanceId))(Right(item))
          }
          .catchAll { error =>
            ZIO.succeed(
              requests.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
                resultMap.insert(req)(Left(error))
              }
            )
          }
      } yield result
    }
  }

  def getEc2Instance(id: primitives.InstanceId): ZQuery[Logging with ec2.Ec2, AwsError, Instance.ReadOnly] =
    ZQuery.fromRequest(GetEc2Instance(id))(ec2InstancesDataSource)

  case class GetImage(id: primitives.ImageId) extends Request[AwsError, Image.ReadOnly]

  val ec2ImagesDataSource: DataSource[Logging with ec2.Ec2, GetImage] = DataSource.Batched.make("ec2-images") { (requests: Chunk[GetImage]) =>
    log.locally(Name("EC2" :: Nil)) {
      (for {
        _ <- log.info(s"DescribeImages (${requests.map(_.id).mkString(", ")})")
        response <- ec2.describeImages(DescribeImagesRequest(imageIds = Some(requests.map(_.id))))
        images <- response.images
        result <- ZIO.foldLeft(images)(CompletedRequestMap.empty) { (resultMap, item) =>
          for {
            imageId <- item.imageId
          } yield resultMap.insert(GetImage(imageId))(Right(item))
        }
      } yield result).catchAll { error =>
        ZIO.succeed(
          requests.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
            resultMap.insert(req)(Left(error))
          }
        )
      }
    }
  }

  def getImage(id: primitives.ImageId): ZQuery[Logging with ec2.Ec2, AwsError, Image.ReadOnly] =
    ZQuery.fromRequest(GetImage(id))(ec2ImagesDataSource)
}
