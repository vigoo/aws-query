package io.github.vigoo.awsquery.query

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.report.{EbAppKey, AsgKey, AsgReport, EbAppReport, EbEnvReport, ElbKey, ElbReport, EbEnvKey, LinkedReport}
import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.awsquery.sources.{asgquery, ebquery, elbquery}
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticbeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.model.{EnvironmentDescription, EnvironmentResourceDescription}
import zio.ZIO
import zio.logging.Logging
import zio.query.ZQuery

trait EbQuery {
  this: Common with ElbQuery with AsgQuery =>

  def getEbEnvReportByInput(input: String): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[EbEnvKey, EbEnvReport]] =
    ebquery.getEnvironmentByName(input).flatMap {
      case Some(env) =>
        getEbEnvReport(env)
      case None =>
        ebquery.getEnvironmentById(input).someOrFail(AwsError.fromThrowable(new IllegalArgumentException(s"Cannot find EB env by input $input"))) >>= getEbEnvReport
    }

  def getEbAppReportByInput(input: String): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[EbAppKey, EbAppReport]] =
    getEbAppReport(input)

  def getEbEnvReport(env: EnvironmentDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[EbEnvKey, EbEnvReport]] =
    cached(env)(_.environmentId.map(EbEnvKey.apply)) { (key: EbEnvKey) =>
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
            region = "us-east-1", // TODO: get from context
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

  private def getEbAppReport(name: elasticbeanstalk.model.primitives.ApplicationName): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[EbAppKey, EbAppReport]] =
    cached(name)(name => ZIO.succeed(EbAppKey(name))) { (key: EbAppKey) =>
      for {
        app <- ebquery.getApplicationByName(name).someOrFail(AwsError.fromThrowable(new IllegalStateException(s"EB Application not found for EB env")))
        envs <- ebquery.getEnvironmentsByAppName(name)
        envReports <- ZQuery.collectAllPar(envs.map(getEbEnvReport))
        numberOfVersions = app.versionsValue.map(_.length).getOrElse(0)
      } yield EbAppReport(
        name,
        region = "us-east-1", // TODO: get from context
        numberOfVersions,
        envReports
      )
    }
}
