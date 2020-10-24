package io.github.vigoo.awsquery.sources

import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.ec2.model.primitives
import io.github.vigoo.zioaws.elasticloadbalancing
import io.github.vigoo.zioaws.elasticloadbalancing.ElasticLoadBalancing
import io.github.vigoo.zioaws.elasticloadbalancing.model.primitives.AccessPointName
import io.github.vigoo.zioaws.elasticloadbalancing.model.{DescribeLoadBalancersRequest, DescribeTagsRequest, LoadBalancerDescription, Tag}
import zio.logging.{Logging, log}
import zio.query.{CompletedRequestMap, DataSource, Request, ZQuery}
import zio.stream.ZStream
import zio.{Chunk, ZIO}

object elbquery {

  sealed trait ElbRequest[+A] extends Request[AwsError, A]

  case class GetAllLoadBalancers() extends ElbRequest[Set[LoadBalancerDescription.ReadOnly]]

  case class GetLoadBalancer(name: AccessPointName) extends ElbRequest[LoadBalancerDescription.ReadOnly]

  case class GetLoadBalancerTags(name: AccessPointName) extends ElbRequest[List[Tag.ReadOnly]]

  val elbDataSource: DataSource[Logging with ElasticLoadBalancing, ElbRequest[Any]] =
    AllOrPerItem.make(
      new AllOrPerItem[Logging with ElasticLoadBalancing, ElbRequest[Any], LoadBalancerDescription.ReadOnly] {
        override val name: String = "ELB"

        override def isGetAll(request: ElbRequest[Any]): Boolean =
          request match {
            case GetAllLoadBalancers() => true
            case _ => false
          }

        override def isPerItem(request: ElbRequest[Any]): Boolean = request match {
          case GetLoadBalancer(name) => true
          case _ => false
        }

        override val allReq: ElbRequest[Any] = GetAllLoadBalancers()

        override def itemToReq(item: LoadBalancerDescription.ReadOnly): ZIO[Logging with ElasticLoadBalancing, AwsError, ElbRequest[Any]] =
          item.loadBalancerName.map(GetLoadBalancer)

        override def getAll(): ZStream[Logging with ElasticLoadBalancing, AwsError, LoadBalancerDescription.ReadOnly] =
          elasticloadbalancing.describeLoadBalancers(DescribeLoadBalancersRequest())

        override def getSome(reqs: Set[ElbRequest[Any]]): ZStream[Logging with ElasticLoadBalancing, AwsError, LoadBalancerDescription.ReadOnly] =
          elasticloadbalancing.describeLoadBalancers(DescribeLoadBalancersRequest(loadBalancerNames =
            Some(reqs.collect { case GetLoadBalancer(name) => name })))

        override def processAdditionalRequests(requests: Chunk[ElbRequest[Any]], partialResult: CompletedRequestMap): ZIO[Logging with ElasticLoadBalancing, Nothing, CompletedRequestMap] = {
          val tagsByName = requests.collect { case GetLoadBalancerTags(name) => name }
          if (tagsByName.nonEmpty) {
            (for {
              _ <- log.info(s"DescribeTags (${tagsByName.mkString(", ")})")
              response <- elasticloadbalancing.describeTags(DescribeTagsRequest(loadBalancerNames = tagsByName))
              descriptions <- response.tagDescriptions
              descriptionMap <- ZIO.foreach(descriptions) { description =>
                for {
                  name <- description.loadBalancerName
                  tags <- description.tags
                } yield name -> tags
              }
            } yield descriptionMap.foldLeft(partialResult) { case (resultMap, (name, tags)) =>
              resultMap.insert(GetLoadBalancerTags(name))(Right(tags))
            }).catchAll { error =>
              ZIO.succeed(
                tagsByName.foldLeft(partialResult) { case (resultMap, name) =>
                  resultMap.insert(GetLoadBalancerTags(name))(Left(error))
                })
            }
          } else {
            ZIO.succeed(partialResult)
          }
        }
      })

  def getAllLoadBalancers: ZQuery[Logging with elasticloadbalancing.ElasticLoadBalancing, AwsError, Set[LoadBalancerDescription.ReadOnly]] =
    ZQuery.fromRequest(GetAllLoadBalancers())(elbDataSource)

  def getLoadBalancer(name: AccessPointName): ZQuery[Logging with elasticloadbalancing.ElasticLoadBalancing, AwsError, LoadBalancerDescription.ReadOnly] =
    ZQuery.fromRequest(GetLoadBalancer(name))(elbDataSource)

  def getLoadBalancerTags(name: AccessPointName): ZQuery[Logging with elasticloadbalancing.ElasticLoadBalancing, AwsError, List[Tag.ReadOnly]] =
    ZQuery.fromRequest(GetLoadBalancerTags(name))(elbDataSource)

  def loadBalancerOf(instanceId: primitives.InstanceId): ZQuery[Logging with elasticloadbalancing.ElasticLoadBalancing, AwsError, Option[LoadBalancerDescription.ReadOnly]] = {
    for {
      all <- getAllLoadBalancers
      result <- ZQuery.fromEffect {
        ZIO.filter(all) { elb =>
          elb.instances.flatMap { instances =>
            ZIO.foreach(instances)(_.instanceId)
              .map(_.contains(instanceId))
          }
        }.map(_.headOption)
      }
    } yield result
  }
}