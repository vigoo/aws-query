package io.github.vigoo.awsquery

import zio._
import io.github.vigoo.zioaws.core
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.ec2
import io.github.vigoo.zioaws.ec2.model._
import io.github.vigoo.zioaws.elasticloadbalancing
import io.github.vigoo.zioaws.elasticloadbalancing.ElasticLoadBalancing
import io.github.vigoo.zioaws.elasticloadbalancing.model.primitives.AccessPointName
import io.github.vigoo.zioaws.elasticloadbalancing.model.{DescribeLoadBalancersRequest, LoadBalancerDescription}
import io.github.vigoo.zioaws.http4s
import zio.logging._
import zio.logging.slf4j._
import zio.query._
import zio.stream.ZStream

object Main extends App {

  case class GetEc2Instance(id: primitives.InstanceId) extends Request[AwsError, Instance.ReadOnly]

  val ec2InstancesDataSource: DataSource[Logging with ec2.Ec2, GetEc2Instance] = new DataSource.Batched[Logging with ec2.Ec2, GetEc2Instance] {
    override def run(requests: Chunk[GetEc2Instance]): ZIO[Logging with ec2.Ec2, Nothing, CompletedRequestMap] = {
      log.info(s"DescribeInstances ${requests.map(_.id).mkString(", ")}") *>
        ec2.describeInstances(DescribeInstancesRequest(instanceIds = Some(requests.map(_.id))))
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
    }

    override val identifier: String = "ec2"
  }

  def getEc2Instance(id: primitives.InstanceId): ZQuery[Logging with ec2.Ec2, AwsError, Instance.ReadOnly] =
    ZQuery.fromRequest(GetEc2Instance(id))(ec2InstancesDataSource)

  class ElbQueries(allLoadBalancers: Map[elasticloadbalancing.model.primitives.AccessPointName, LoadBalancerDescription.ReadOnly]) {

    import ElbQueries.GetLoadBalancer

    private val dataSource = new DataSource.Batched[Logging with elasticloadbalancing.ElasticLoadBalancing, GetLoadBalancer] {
      override def run(requests: Chunk[ElbQueries.GetLoadBalancer]): ZIO[Logging with ElasticLoadBalancing, Nothing, CompletedRequestMap] = {
        val (prefetched, nonPrefetched) = requests.partition(req => allLoadBalancers.contains(req.name))
        for {
          a <- runPrefetched(prefetched)
          b <- runNonPrefetched(nonPrefetched)
        } yield a ++ b
      }

      private def runPrefetched(requests: Chunk[ElbQueries.GetLoadBalancer]): ZIO[Logging with ElasticLoadBalancing, Nothing, CompletedRequestMap] = {
        ZIO.succeed(requests.foldLeft(CompletedRequestMap.empty) { case (result, req) =>
          result.insert(GetLoadBalancer(req.name))(Right(allLoadBalancers(req.name)))
        })
      }

      private def runNonPrefetched(requests: Chunk[ElbQueries.GetLoadBalancer]): ZIO[Logging with ElasticLoadBalancing, Nothing, CompletedRequestMap] = {
        log.info(s"DescribeLoadBalancers ${requests.map(_.name).mkString(", ")}") *>
          elasticloadbalancing.describeLoadBalancers(DescribeLoadBalancersRequest(loadBalancerNames = Some(requests.map(_.name))))
            .foldM(CompletedRequestMap.empty) { (resultMap, item) =>
              for {
                elbName <- item.loadBalancerName
              } yield resultMap.insert(GetLoadBalancer(elbName))(Right(item))
            }
            .catchAll { error =>
              ZIO.succeed(
                requests.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
                  resultMap.insert(req)(Left(error))
                }
              )
            }
      }

      override val identifier: String = "elb"
    }

    def allPrefetched: ZQuery[Any, AwsError, Seq[LoadBalancerDescription.ReadOnly]] =
      ZQuery.succeed(allLoadBalancers.values.toSeq)

    def getLoadBalancer(name: AccessPointName): ZQuery[Logging with elasticloadbalancing.ElasticLoadBalancing, AwsError, LoadBalancerDescription.ReadOnly] =
      ZQuery.fromRequest(GetLoadBalancer(name))(dataSource)

    def loadBalancerOf(instanceId: primitives.InstanceId): ZQuery[Logging, AwsError, Option[LoadBalancerDescription.ReadOnly]] = {
      ZQuery.fromEffect {
        ZIO.filter(allLoadBalancers.values.toList) { elb =>
          elb.instances.flatMap { instances =>
            ZIO.foreach(instances)(_.instanceId)
              .map(_.contains(instanceId))
          }
        }.map(_.headOption)
      }
    }
  }

  def getSiblingsOfInstance(elbQueries: ElbQueries, instance: primitives.InstanceId): ZQuery[Logging with ec2.Ec2, AwsError, Set[Instance.ReadOnly]] =
    for {
      elb <- elbQueries.loadBalancerOf(instance)
      instances <- elb match {
        case Some(elb) =>
          for {
            instanceMembers <- ZQuery.fromEffect(elb.instances)
            instanceIds <- ZQuery.fromEffect(ZIO.foreach(instanceMembers)(_.instanceId))
            instances <- ZQuery.foreachPar(instanceIds)(getEc2Instance)
          } yield instances
        case None =>
          ZQuery.fail(AwsError.fromThrowable(new RuntimeException(s"Could not find ELB for instance $instance")))
      }
    } yield instances.toSet

  object ElbQueries {

    case class GetLoadBalancer(name: AccessPointName) extends Request[AwsError, LoadBalancerDescription.ReadOnly]

    def apply(): ZIO[Logging with elasticloadbalancing.ElasticLoadBalancing, AwsError, ElbQueries] =
      for {
        _ <- log.info(s"Getting all ELBs")
        elbs <- elasticloadbalancing.describeLoadBalancers(DescribeLoadBalancersRequest()).runCollect
        allLoadBalancers <- ZIO.foreach(elbs) { elb =>
          elb.loadBalancerName.map { name => (name -> elb) }
        }
        _ <- log.info(s"Got ${allLoadBalancers.size} ELBs")
      } yield new ElbQueries(allLoadBalancers.toMap)
  }

  private def runQuery(instanceId: String): ZIO[Logging with ec2.Ec2 with elasticloadbalancing.ElasticLoadBalancing, AwsError, Unit] =
    for {
      elbQueries <- ElbQueries()
      elb <- elbQueries.loadBalancerOf(instanceId).run
      elbName <- elb.map(_.loadBalancerName).getOrElse(ZIO.succeed("???"))
      instances <- getSiblingsOfInstance(elbQueries, instanceId).run
      _ <- log.info(s"Instances in $elbName:")
      _ <- ZIO.foreach(instances) { instance =>
        for {
          id <- instance.instanceId
          startedAt <- instance.launchTime
          _ <- log.info(s"- $id started at $startedAt")
        } yield ()
      }
      _ <- log.info("And the union of all the siblings of all instances:")
      all <- ZQuery.foreachPar(instances.toList) { instance =>
        for {
          id <- ZQuery.fromEffect(instance.instanceId)
          siblings <- getSiblingsOfInstance(elbQueries, id)
        } yield siblings
      }.map(_.fold(Set.empty)(_ union _)).run
      _ <- ZIO.foreach(all) { instance =>
        for {
          id <- instance.instanceId
          startedAt <- instance.launchTime
          _ <- log.info(s"- $id started at $startedAt")
        } yield ()
      }
    } yield ()

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
          elasticloadbalancing.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)))) ++
        logging
    for {
      _ <- runQuery(args(1))
        .provideLayer(layer)
        .catchAll { error =>
          console.putStrLnErr(error.toString)
        }
    } yield ExitCode.success
  }
}