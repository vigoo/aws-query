package io.github.vigoo.awsquery.query

import io.github.vigoo.awsquery.Main.Parameters
import io.github.vigoo.awsquery.query.Common.QueryEnv
import io.github.vigoo.awsquery.report.{Ec2InstanceKey, Ec2InstanceReport, LinkedReport}
import io.github.vigoo.awsquery.sources.{ec2query, elbquery}
import io.github.vigoo.clipp.zioapi.config.parameters
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.ec2
import zio.ZIO
import zio.query.ZQuery

trait Ec2Query {
  this: Common with ElbQuery =>

  def getInstanceReport(instanceId: ec2.model.primitives.InstanceId): ZQuery[QueryEnv, AwsError, LinkedReport[Ec2InstanceKey, Ec2InstanceReport]] =
    cached(instanceId)((id: ec2.model.primitives.InstanceId) => ZIO.succeed(Ec2InstanceKey(id))) { _ =>
      for {
        instance <- ec2query.getEc2Instance(instanceId)
        imageId <- ZQuery.fromEffect(instance.imageId)
        imgElb <- (ec2query.getImage(imageId) <&> elbquery.loadBalancerOf(instanceId))
        (image, elb) = imgElb
        elbReport <- optionally(elb)(getElbReport)

        result <- ZQuery.fromEffect {
          for {
            state <- instance.state
            stateName <- state.name
            tagList <- instance.tags
            tags <- ZIO.foreach(tagList) { tag =>
              for {
                key <- tag.key
                value <- tag.value
              } yield key -> value
            }
            instanceType <- instance.instanceType
            securityGroupList <- instance.securityGroups
            securityGroups <- ZIO.foreach(securityGroupList) { secGroup =>
              for {
                id <- secGroup.groupId
                name <- secGroup.groupName
              } yield id -> name
            }
            amiId <- image.imageId
            amiName <- image.name
            instanceProfile <- instance.iamInstanceProfile
            instanceProfileArn <- instanceProfile.arn
            instanceProfileId <- instanceProfile.id
            sshKeyName <- instance.keyName
            launchedAt <- instance.launchTime
            region <- parameters[Parameters].map(_.region)
          } yield Ec2InstanceReport(
            instanceId,
            region,
            instance.vpcIdValue,
            instance.subnetIdValue,
            stateName,
            tags.toMap,
            instance.publicIpAddressValue,
            instance.publicDnsNameValue,
            instance.privateIpAddressValue,
            instance.privateDnsNameValue,
            instanceType,
            securityGroups.toMap,
            amiId,
            amiName,
            instanceProfileArn,
            instanceProfileId,
            sshKeyName,
            launchedAt,
            elbReport
          )
        }
      } yield result
    }
}
