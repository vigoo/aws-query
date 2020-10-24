package io.github.vigoo.awsquery

import io.github.vigoo.zioaws.ec2.model.primitives.DateTime
import io.github.vigoo.zioaws.{autoscaling, ec2, elasticbeanstalk, elasticloadbalancing}
import zio.stm.TMap
import zio.{Has, UIO, ZIO, ZLayer}

package object report {
  type ReportCache = Has[ReportCache.Service]

  object ReportCache {

    trait Service {
      def store[A <: Report](reportKey: ReportKey, value: A): UIO[Unit]

      def retrieve[A <: Report](key: ReportKey): UIO[Option[A]]
    }

  }

  val live: ZLayer[Any, Nothing, ReportCache] = ZLayer.fromEffect {
    for {
      cache <- TMap.empty[ReportKey, Report].commit
    } yield new ReportCache.Service {
      override def store[A <: Report](reportKey: ReportKey, value: A): UIO[Unit] =
        cache.put(reportKey, value).commit

      override def retrieve[A <: Report](key: ReportKey): UIO[Option[A]] =
        for {
          report <- cache.get(key).commit
          reportA <- ZIO.effect(report.map(_.asInstanceOf[A])).orDie
        } yield reportA
    }
  }

  def store[A <: Report](key: ReportKey, value: A): ZIO[ReportCache, Nothing, Unit] = ZIO.accessM(_.get.store(key, value))

  def retrieve[A <: Report](key: ReportKey): ZIO[ReportCache, Nothing, Option[A]] = ZIO.accessM(_.get.retrieve(key))

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
