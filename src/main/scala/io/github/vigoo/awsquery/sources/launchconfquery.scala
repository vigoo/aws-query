package io.github.vigoo.awsquery.sources

import io.github.vigoo.zioaws.autoscaling
import io.github.vigoo.zioaws.autoscaling.AutoScaling
import io.github.vigoo.zioaws.autoscaling.model._
import io.github.vigoo.zioaws.autoscaling.model.primitives._
import io.github.vigoo.zioaws.core.AwsError
import zio.ZIO
import zio.logging.Logging
import zio.query.{DataSource, Request, ZQuery}
import zio.stream.ZStream

object launchconfquery {

  sealed trait LaunchConfRequest[+A] extends Request[AwsError, A]
  case class GetAllLaunchConfigurations() extends LaunchConfRequest[Set[LaunchConfiguration.ReadOnly]]
  case class GetLaunchConfiguration(name: ResourceName) extends LaunchConfRequest[LaunchConfiguration.ReadOnly]

  val launchConfDataSource: DataSource[Logging with AutoScaling, LaunchConfRequest[Any]] =
    AllOrPerItem.make(new AllOrPerItem[Logging with AutoScaling, LaunchConfRequest[Any], LaunchConfiguration.ReadOnly] {
      override val name: String = "launchconf"

      override def isGetAll(request: LaunchConfRequest[Any]): Boolean =
        request match {
          case GetAllLaunchConfigurations() => true
          case GetLaunchConfiguration(name) => false
        }

      override def isPerItem(request: LaunchConfRequest[Any]): Boolean =
        request match {
          case GetAllLaunchConfigurations() => false
          case GetLaunchConfiguration(name) => true
        }

      override val allReq: LaunchConfRequest[Any] = GetAllLaunchConfigurations()

      override def itemToReq(item: LaunchConfiguration.ReadOnly): ZIO[Logging with AutoScaling, AwsError, LaunchConfRequest[Any]] =
        item.launchConfigurationName.map(GetLaunchConfiguration)

      override def getAll(): ZStream[Logging with AutoScaling, AwsError, LaunchConfiguration.ReadOnly] =
        autoscaling.describeLaunchConfigurations(DescribeLaunchConfigurationsRequest())

      override def getSome(reqs: Set[LaunchConfRequest[Any]]): ZStream[Logging with AutoScaling, AwsError, LaunchConfiguration.ReadOnly] =
        autoscaling.describeLaunchConfigurations(DescribeLaunchConfigurationsRequest(launchConfigurationNames =
          Some(reqs.collect {
            case GetLaunchConfiguration(name) => name
          })))
    })

  def getAllLaunchConfigurations(): ZQuery[Logging with AutoScaling, AwsError, Set[LaunchConfiguration.ReadOnly]] =
    ZQuery.fromRequest(GetAllLaunchConfigurations())(launchConfDataSource)

  def getLaunchConfiguration(name: ResourceName): ZQuery[Logging with AutoScaling, AwsError, LaunchConfiguration.ReadOnly] =
    ZQuery.fromRequest(GetLaunchConfiguration(name))(launchConfDataSource)

}
