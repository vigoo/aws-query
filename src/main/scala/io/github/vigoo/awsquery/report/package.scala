package io.github.vigoo.awsquery

import io.github.vigoo.zioaws.{autoscaling, ec2, elasticbeanstalk, elasticloadbalancing}
import zio.{Has, UIO, ZIO}

package object report {
  type ReportCache = Has[ReportCache.Service]

  object ReportCache {
    trait Service {
      def store[A <: Report](reportKey: ReportKey, value: A): UIO[Unit]
      def retrieve[A <: Report](key: ReportKey): UIO[Option[A]]
    }
  }

  def store[A <: Report](key: ReportKey, value: A): ZIO[ReportCache, Nothing, Unit] = ZIO.accessM(_.get.store(key, value))
  def retrieve[A <: Report](key: ReportKey): ZIO[ReportCache, Nothing, Option[A]] = ZIO.accessM(_.get.retrieve(key))

  sealed trait ReportKey
  final case class Ec2InstanceKey(instanceId: ec2.model.primitives.InstanceId) extends ReportKey
  final case class ElbKey(name: elasticloadbalancing.model.primitives.AccessPointName) extends ReportKey
  final case class EnvKey(id: elasticbeanstalk.model.primitives.EnvironmentId) extends ReportKey
  final case class AppKey(name: elasticbeanstalk.model.primitives.ApplicationName) extends ReportKey
  final case class AsgKey(id: autoscaling.model.primitives.ResourceName) extends ReportKey

  sealed trait Report
  final case class Ec2InstanceReport() extends Report
  final case class ElbReport() extends Report
  final case class EbEnvReport() extends Report
  final case class EbAppReport() extends Report
  final case class AsgReport() extends Report
}
