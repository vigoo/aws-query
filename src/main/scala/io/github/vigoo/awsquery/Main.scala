package io.github.vigoo.awsquery

import io.github.vigoo.awsquery.report._
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.autoscaling.model.{AutoScalingGroup, LaunchConfiguration}
import io.github.vigoo.zioaws.elasticloadbalancing.model.LoadBalancerDescription
import io.github.vigoo.zioaws.elasticbeanstalk.model.{ApplicationDescription, EnvironmentDescription, EnvironmentResourceDescription}
import io.github.vigoo.zioaws.elasticbeanstalk.model.primitives.EnvironmentId
import io.github.vigoo.zioaws.elasticloadbalancing.model.primitives.AccessPointName
import io.github.vigoo.zioaws.{autoscaling, core, ec2, elasticbeanstalk, elasticloadbalancing, netty}
import org.apache.logging.log4j.LogManager
import zio._
import zio.console.Console
import zio.logging._
import zio.logging.slf4j._
import zio.query.{DataSource, DataSourceAspect, ZQuery}
import zio.query.Described._

import scala.util.matching.Regex

object Main extends App {

  import sources._

  type AllServices = ec2.Ec2 with elasticloadbalancing.ElasticLoadBalancing with elasticbeanstalk.ElasticBeanstalk with autoscaling.AutoScaling

  val cloudFormationStackRegex: Regex = """^awseb-(.*)-stack$""".r

  private def optionally[R, E, A, B](value: Option[A])(f: A => ZQuery[R, E, B]): ZQuery[R, E, Option[B]] =
    value match {
      case Some(value) => f(value).map(Some.apply)
      case None => ZQuery.none
    }

  private def cached[R <: ReportCache with Logging, A, B <: Report, K <: ReportKey](input: A)(keyFn: A => ZIO[Any, AwsError, K])(query: K => ZQuery[R, AwsError, B]): ZQuery[R, AwsError, LinkedReport[K, B]] =
    for {
      key <- ZQuery.fromEffect(keyFn(input))
      env <- ZQuery.environment[R]
      _ <- storeIfNew(
        key,
        query(key).provide(env ? "provided environment")
      )
    } yield LinkedReport[K, B](key)

  private def getEnvironmentsLoadBalancerReports(resource: EnvironmentResourceDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, List[LinkedReport[ElbKey, ElbReport]]] =
    for {
      elbNames <- ZQuery.fromEffect {
        for {
          elbs <- resource.loadBalancers
          elbNames <- ZIO.foreach(elbs)(_.name)
        } yield elbNames
      }
      elbs <- ZQuery.collectAllPar(
        elbNames.map(name => elbquery.getLoadBalancer(name) >>= getElbReport))
    } yield elbs

  private def getEnvironmentsAutoScalingGroupReports(resource: EnvironmentResourceDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, List[LinkedReport[AsgKey, AsgReport]]] =
    for {
      asgNames <- ZQuery.fromEffect {
        for {
          asgs <- resource.autoScalingGroups
          asgNames <- ZIO.foreach(asgs)(_.name)
        } yield asgNames
      }
      asgs <- ZQuery.collectAllPar(
        asgNames.map(name => asgquery.getAutoScalingGroup(name) >>= getAsgReport))
    } yield asgs

  private def getEbAppReport(name: elasticbeanstalk.model.primitives.ApplicationName): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[AppKey, EbAppReport]] =
    cached(name)(name => ZIO.succeed(AppKey(name))) { (key: AppKey) =>
      for {
        app <- ebquery.getApplicationByName(name).someOrFail(AwsError.fromThrowable(new IllegalStateException(s"EB Application not found for EB env"))) // TODO: review this
        envs <- ebquery.getEnvironmentsByAppName(name)
        envReports <- ZQuery.collectAllPar(envs.map(getEbEnvReport))
      } yield EbAppReport(
        name,
        app.versionsValue.map(_.length).getOrElse(0),
        envReports
      )
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

  private def getAsgEbEnv(asg: AutoScalingGroup.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, Option[LinkedReport[EnvKey, EbEnvReport]]] =
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

  private def getAsgReport(asg: AutoScalingGroup.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[AsgKey, AsgReport]] =
    cached(asg)(_.autoScalingGroupName.map(AsgKey.apply)) { (key: AsgKey) =>
      (getAsgElbs(asg) <&> getAsgEbEnv(asg) <&> getAsgLaunchConfig(asg)).map {
        case ((elbs, ebEnvReport), launchConfigReport) =>
          AsgReport(elbs, ebEnvReport, launchConfigReport)
      }
    }

  private def getEbEnvReport(env: EnvironmentDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[EnvKey, EbEnvReport]] =
    cached(env)(_.environmentId.map(EnvKey.apply)) { (key: EnvKey) =>
      for {
        appName <- ZQuery.fromEffect(env.applicationName)
        resource <- ebquery.getEnvironmentResource(key.id)
        subQueries <-
          getEnvironmentsLoadBalancerReports(resource) <&>
            getEnvironmentsAutoScalingGroupReports(resource) <&>
            getEbAppReport(appName)
        ((elbs, asgs), app) = subQueries
        result <- ZQuery.fromEffect {
          for {
            name <- env.environmentName
            appName <- env.applicationName
            health <- env.health
            version <- env.versionLabel
            instanceCount <- resource.instances.map(_.length)
          } yield EbEnvReport(
            name,
            key.id,
            appName,
            health,
            version,
            asgs,
            elbs,
            app,
            instanceCount
          )
        }
      } yield result
    }

  private def getElbReport(elb: LoadBalancerDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[ElbKey, ElbReport]] =
    cached(elb)(_.loadBalancerName.map(ElbKey.apply)) { (key: ElbKey) =>
      for {
        tagList <- elbquery.getLoadBalancerTags(key.name)
        cfStackNameTag = tagList.find(_.keyValue == "aws:cloudformation:stack-name")
        ebName <- optionally(cfStackNameTag) { tag =>
          ZQuery.fromEffect(tag.value).map(tagValue => cloudFormationStackRegex.findFirstMatchIn(tagValue).map(_.group(1)))
        }.map(_.flatten)
        env <- optionally(ebName) { name =>
          (ebquery.getEnvironmentById(name) <&> ebquery.getEnvironmentByName(name)).map { case (a, b) => a.orElse(b) }
        }.map(_.flatten)
        ebEnvReport <- optionally(env)(getEbEnvReport)
        result <- ZQuery.fromEffect {
          for {
            tags <- ZIO.foreach(tagList) { tag =>
              for {
                key <- tag.key
                value <- tag.value
              } yield key -> value
            }
            availabilityZones <- elb.availabilityZones
            listenerDescriptions <- elb.listenerDescriptions
            listeners <- ZIO.foreach(listenerDescriptions)(_.listener)
            instances <- elb.instances
            instanceIds <- ZIO.foreach(instances)(_.instanceId)
          } yield ElbReport(
            key.name,
            tags.toMap,
            availabilityZones,
            listeners,
            instanceIds,
            ebEnvReport
          )
        }
      } yield result
    }

  private def getInstanceReport(instanceId: ec2.model.primitives.InstanceId): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[Ec2InstanceKey, Ec2InstanceReport]] =
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
            sshKeyName <- instance.keyName
            launchedAt <- instance.launchTime
          } yield Ec2InstanceReport(
            instanceId,
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
            sshKeyName,
            launchedAt,
            elbReport
          )
        }
      } yield result
    }

  private def runQuery(instanceId: String): ZIO[Console with Logging with ReportCache with AllServices, AwsError, Unit] =
    for {
      result <- getInstanceReport(instanceId).run
      r <- retrieve(result.key)
      _ <- console.putStrLn(s"Result: $r")
    } yield ()

  // TODOs
  // logging? and rate limiting as aspects
  // unify common code
  // "execution graph dump" aspect for generating diagrams for the post?
  // implicit withFilter to make zipPars nicer?

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val logging = Slf4jLogger.make { (context, message) =>
      message
    }
    val finalLayer =
      (netty.client() >>> core.config.default >>>
        (
          ec2.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
            elasticloadbalancing.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
            elasticbeanstalk.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1)) ++
            autoscaling.customized(_.region(software.amazon.awssdk.regions.Region.US_EAST_1))
          )
        ) ++ logging ++ report.live ++ Console.any
    for {
      _ <- runQuery(args(1))
        .provideLayer(finalLayer)
        .catchAll { error =>
          console.putStrLnErr(error.toString)
        }
      _ <- ZIO.effect(LogManager.shutdown()).orDie
      _ <- console.putStrLn("Finished.")
    } yield ExitCode.success
  }
}