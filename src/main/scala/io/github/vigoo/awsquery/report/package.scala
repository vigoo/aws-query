package io.github.vigoo.awsquery

import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.ec2.model.primitives.DateTime
import io.github.vigoo.zioaws.{autoscaling, ec2, elasticbeanstalk, elasticloadbalancing}
import zio.query.ZQuery
import zio.stm.{TMap, ZSTM}
import zio.{Has, Promise, UIO, ZIO, ZLayer}

package object report {
  type ReportCache = Has[ReportCache.Service]

  object ReportCache {

    trait Service {
      def storeIfNew[A <: Report](reportKey: ReportKey, query: ZQuery[Any, AwsError, A]): ZQuery[Any, AwsError, Boolean]

      def retrieve[A <: Report](key: ReportKey): ZIO[Any, AwsError, Option[A]]
    }

  }

  val live: ZLayer[Any, Nothing, ReportCache] = {
    for {
      cache <- TMap.empty[ReportKey, Promise[AwsError, Report]].commit
    } yield new ReportCache.Service {
      override def storeIfNew[A <: Report](reportKey: ReportKey, query: ZQuery[Any, AwsError, A]): ZQuery[Any, AwsError, Boolean] =
        ZQuery.fromEffect {
          for {
            promise <- Promise.make[AwsError, Report]
            finalQuery <- cache.get(reportKey).flatMap {
              case Some(report) =>
                // replacing the query with the cached value
                ZSTM.succeed(ZQuery.succeed(false))
              case None =>
                // replacing the query with the cached value
                cache.put(reportKey, promise).map { _ =>
                  query.foldM(
                    failure => ZQuery.fromEffect(promise.fail(failure)) *> ZQuery.fail(failure),
                    success => ZQuery.fromEffect(promise.succeed(success))
                  )
                }
            }.commit
          } yield finalQuery
        }.flatMap(identity)

      override def retrieve[A <: Report](key: ReportKey): ZIO[Any, AwsError, Option[A]] =
        for {
          reportPromise <- cache.get(key).commit
          result <- reportPromise match {
            case None => ZIO.none
            case Some(promise) => promise.await.map(r => Some(r.asInstanceOf[A]))
          }
        } yield result
    }
  }.toLayer

  def storeIfNew[A <: Report](key: ReportKey, query: ZQuery[Any, AwsError, A]): ZQuery[ReportCache, AwsError, Boolean] = ZQuery.accessM(_.get.storeIfNew(key, query))

  def retrieve[A <: Report](key: ReportKey): ZIO[ReportCache, AwsError, Option[A]] = ZIO.accessM(_.get.retrieve(key))

  case class LinkedReport[K <: ReportKey, R <: Report](key: K)


  sealed trait ReportKey

  final case class Ec2InstanceKey(instanceId: ec2.model.primitives.InstanceId) extends ReportKey

  final case class ElbKey(name: elasticloadbalancing.model.primitives.AccessPointName) extends ReportKey

  final case class EnvKey(id: elasticbeanstalk.model.primitives.EnvironmentId) extends ReportKey

  final case class AppKey(name: elasticbeanstalk.model.primitives.ApplicationName) extends ReportKey

  final case class AsgKey(id: autoscaling.model.primitives.ResourceName) extends ReportKey

  final case class LaunchConfigKey(name: autoscaling.model.primitives.ResourceName) extends ReportKey


  sealed trait Report

  final case class Ec2InstanceReport(instanceId: ec2.model.primitives.InstanceId,
                                     vpcId: Option[String],
                                     subnetId: Option[String],
                                     state: ec2.model.InstanceStateName,
                                     tags: Map[String, String],
                                     publicIp: Option[String],
                                     publicDns: Option[String],
                                     privateIp: Option[String],
                                     privateDns: Option[String],
                                     instanceType: ec2.model.InstanceType,
                                     securityGroups: Map[String, String],
                                     amiId: String,
                                     amiName: String,
                                     instanceProfileArn: String,
                                     sshKeyName: String,
                                     launchedAt: DateTime,
                                     elb: Option[LinkedReport[ElbKey, ElbReport]]
                                    ) extends Report

  final case class ElbReport(name: elasticloadbalancing.model.primitives.AccessPointName,
                             tags: Map[String, String],
                             availabilityZones: List[String],
                             listeners: List[elasticloadbalancing.model.Listener.ReadOnly],
                             instancesIds: List[elasticloadbalancing.model.primitives.InstanceId],
                             ebEnv: Option[LinkedReport[EnvKey, EbEnvReport]]) extends Report

  final case class EbEnvReport(name: elasticbeanstalk.model.primitives.EnvironmentName,
                               id: elasticbeanstalk.model.primitives.EnvironmentId,
                               appName: elasticbeanstalk.model.primitives.ApplicationName,
                               health: elasticbeanstalk.model.EnvironmentHealth,
                               version: elasticbeanstalk.model.primitives.VersionLabel,
                               asgs: List[LinkedReport[AsgKey, AsgReport]],
                               elbs: List[LinkedReport[ElbKey, ElbReport]],
                               app: LinkedReport[AppKey, EbAppReport],
                               instanceCount: Int
                              ) extends Report

  final case class EbAppReport(name: elasticbeanstalk.model.primitives.ApplicationName,
                               numberOfVersions: Int,
                               ebEnvs: List[LinkedReport[EnvKey, EbEnvReport]]) extends Report

  final case class AsgReport(loadBalancers: List[LinkedReport[ElbKey, ElbReport]],
                             ebEnv: Option[LinkedReport[EnvKey, EbEnvReport]],
                             launchConfiguration: LinkedReport[LaunchConfigKey, LaunchConfigReport]) extends Report

  final case class LaunchConfigReport(name: autoscaling.model.primitives.ResourceName,
                                      createdAt: DateTime,
                                      amiId: String,
                                      instanceProfileArn: Option[String],
                                      instanceType: String,
                                      securityGroups: List[String]) extends Report

}
