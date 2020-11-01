package io.github.vigoo.awsquery.query

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.report.{AsgKey, AsgReport, EbEnvKey, EbEnvReport, ElbKey, ElbReport, LaunchConfigKey, LaunchConfigReport, LinkedReport}
import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.awsquery.sources.{asgquery, ebquery, elbquery, launchconfquery}
import io.github.vigoo.zioaws.autoscaling.model.{AutoScalingGroup, LaunchConfiguration}
import io.github.vigoo.zioaws.core.AwsError
import zio.ZIO
import zio.logging.Logging
import zio.query.ZQuery

trait AsgQuery {
  this: Common with EbQuery with ElbQuery =>

  def getAsgReportByInput(input: String): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[AsgKey, AsgReport]] = {
    (asgquery.getAutoScalingGroup(input).optional <&> asgquery.getAutoScalingGroupByLaunchConfiguration(input)).flatMap {
      case (a, b) =>
        val asg = a.orElse(b)
          .map((asg: AutoScalingGroup.ReadOnly) => ZQuery.succeed(asg))
          .getOrElse(ZQuery.fail(AwsError.fromThrowable(new IllegalArgumentException(s"Cannot find ASG by input $input"))))
        asg >>= getAsgReport
    }
  }

  def getAsgReport(asg: AutoScalingGroup.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[AsgKey, AsgReport]] =
    cached(asg)(_.autoScalingGroupName.map(AsgKey.apply)) { (key: AsgKey) =>
      (getAsgElbs(asg) <&> getAsgEbEnv(asg) <&> getAsgLaunchConfig(asg)).flatMap {
        case ((elbs, ebEnvReport), launchConfigReport) =>
          ZQuery.fromEffect {
            for {
              tagList <- asg.tags
              tags <- ZIO.foreach(tagList) { tag =>
                for {
                  key <- tag.key
                  value <- tag.value
                } yield key -> value
              }
            } yield AsgReport(
              key.id,
              region = "us-east-1", // TODO: get from context
              asg.instancesValue.map(_.size).getOrElse(0),
              asg.minSizeValue,
              asg.maxSizeValue,
              asg.desiredCapacityValue,
              asg.createdTimeValue,
              tags.toMap,
              elbs,
              ebEnvReport,
              launchConfigReport
            )
          }
      }
    }

  private def getLaunchConfigReport(lc: LaunchConfiguration.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[LaunchConfigKey, LaunchConfigReport]] =
    cached(lc)(_.launchConfigurationName.map(LaunchConfigKey.apply)) { (key: LaunchConfigKey) =>
      ZQuery.fromEffect {
        for {
          createdAt <- lc.createdTime
          amiId <- lc.imageId
          instanceProfileArn = lc.iamInstanceProfileValue
          instanceType <- lc.instanceType
          securityGroups <- lc.securityGroups
        } yield LaunchConfigReport(
          key.name,
          region = "us-east-1", // TODO: get from context
          createdAt,
          amiId,
          instanceProfileArn,
          instanceType,
          securityGroups
        )
      }
    }

  private def getAsgElbs(asg: AutoScalingGroup.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, List[LinkedReport[ElbKey, ElbReport]]] =
    for {
      elbNames <- ZQuery.fromEffect(asg.loadBalancerNames)
      result <- ZQuery.collectAllPar(elbNames.map(name => elbquery.getLoadBalancer(name) >>= getElbReport))
    } yield result

  private def getAsgEbEnv(asg: AutoScalingGroup.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, Option[LinkedReport[EbEnvKey, EbEnvReport]]] =
    for {
      tagList <- ZQuery.fromEffect(asg.tags)
      ebEnvNameTag = tagList.find(_.keyValue.contains("elasticbeanstalk:environment-name"))
      ebName <- optionally(ebEnvNameTag) { tag =>
        ZQuery.fromEffect(tag.value)
      }
      env <- optionally(ebName) { name =>
        ebquery.getEnvironmentByName(name)
      }.map(_.flatten)
      result <- optionally(env)(getEbEnvReport)
    } yield result

  private def getAsgLaunchConfig(asg: AutoScalingGroup.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[LaunchConfigKey, LaunchConfigReport]] =
    for {
      launchConfigName <- ZQuery.fromEffect(asg.launchConfigurationName)
      launchConfig <- launchconfquery.getLaunchConfiguration(launchConfigName)
      result <- getLaunchConfigReport(launchConfig)
    } yield result

}
