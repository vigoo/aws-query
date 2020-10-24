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

object asgquery {

  sealed trait AsgRequest[+A] extends Request[AwsError, A]
  case class GetAllAutoScalingGroups() extends AsgRequest[Set[AutoScalingGroup.ReadOnly]]
  case class GetAutoScalingGroup(name: ResourceName) extends AsgRequest[AutoScalingGroup.ReadOnly]

  val asgDataSource: DataSource[Logging with AutoScaling, AsgRequest[Any]] =
    AllOrPerItem.make(new AllOrPerItem[Logging with AutoScaling, AsgRequest[Any], AutoScalingGroup.ReadOnly] {
      override val name: String = "ASG"

      override def isGetAll(request: AsgRequest[Any]): PropagateAtLaunch =
        request match {
          case GetAllAutoScalingGroups() => true
          case GetAutoScalingGroup(name) => false
        }

      override def isPerItem(request: AsgRequest[Any]): PropagateAtLaunch =
        request match {
          case GetAllAutoScalingGroups() => false
          case GetAutoScalingGroup(name) => true
        }

      override val allReq: AsgRequest[Any] = GetAllAutoScalingGroups()

      override def itemToReq(item: AutoScalingGroup.ReadOnly): ZIO[Logging with AutoScaling, AwsError, AsgRequest[Any]] =
        item.autoScalingGroupName.map(GetAutoScalingGroup)

      override def getAll(): ZStream[Logging with AutoScaling, AwsError, AutoScalingGroup.ReadOnly] =
        autoscaling.describeAutoScalingGroups(DescribeAutoScalingGroupsRequest())

      override def getSome(reqs: Set[AsgRequest[Any]]): ZStream[Logging with AutoScaling, AwsError, AutoScalingGroup.ReadOnly] =
        autoscaling.describeAutoScalingGroups(DescribeAutoScalingGroupsRequest(autoScalingGroupNames =
          Some(reqs.collect {
            case GetAutoScalingGroup(name) => name
          })))
    })

  def getAllAutoScalingGroups(): ZQuery[Logging with AutoScaling, AwsError, Set[AutoScalingGroup.ReadOnly]] =
    ZQuery.fromRequest(GetAllAutoScalingGroups())(asgDataSource)

  def getAutoScalingGroup(name: ResourceName): ZQuery[Logging with AutoScaling, AwsError, AutoScalingGroup.ReadOnly] =
    ZQuery.fromRequest(GetAutoScalingGroup(name))(asgDataSource)

  def getAutoScalingGroupByLaunchConfiguration(launchConfigurationName: ResourceName): ZQuery[Logging with AutoScaling, AwsError, Option[AutoScalingGroup.ReadOnly]] =
    for {
      asgs <- getAllAutoScalingGroups()
      map <- ZQuery.foreachPar(asgs.toList)(asg => ZQuery.fromEffect(asg.launchConfigurationName.map(_ -> asg)))
    } yield map.toMap.get(launchConfigurationName)
}
