package io.github.vigoo.awsquery.query

import io.github.vigoo.awsquery.Main.Parameters
import io.github.vigoo.awsquery.query.Common.{AllServices, QueryEnv}
import io.github.vigoo.awsquery.report.{AsgKey, AsgReport, EbAppKey, EbAppReport, EbEnvKey, EbEnvReport, ElbKey, ElbReport, LinkedReport}
import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.awsquery.sources.{asgquery, ebquery, elbquery}
import io.github.vigoo.clipp.zioapi.config.parameters
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticbeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.model.{EnvironmentDescription, EnvironmentResourceDescription}
import zio.ZIO
import zio.logging.Logging
import zio.query.ZQuery

trait EbQuery {
  this: Common with ElbQuery with AsgQuery =>

  def getEbEnvReportByInput(input: String): ZQuery[QueryEnv, AwsError, LinkedReport[EbEnvKey, EbEnvReport]] =
    ebquery.getEnvironmentByName(input).flatMap {
      case Some(env) =>
        getEbEnvReport(env)
      case None =>
        ebquery.getEnvironmentById(input).someOrFail(AwsError.fromThrowable(new IllegalArgumentException(s"Cannot find EB env by input $input"))) >>= getEbEnvReport
    }

  def getEbAppReportByInput(input: String): ZQuery[QueryEnv, AwsError, LinkedReport[EbAppKey, EbAppReport]] =
    getEbAppReport(input)

  def getEbEnvReport(env: EnvironmentDescription.ReadOnly): ZQuery[QueryEnv, AwsError, LinkedReport[EbEnvKey, EbEnvReport]] =
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
            region <- parameters[Parameters].map(_.region)
          } yield EbEnvReport(
            name,
            region,
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

  private def getEnvironmentsLoadBalancerReports(resource: EnvironmentResourceDescription.ReadOnly): ZQuery[QueryEnv, AwsError, List[LinkedReport[ElbKey, ElbReport]]] =
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

  private def getEnvironmentsAutoScalingGroupReports(resource: EnvironmentResourceDescription.ReadOnly): ZQuery[QueryEnv, AwsError, List[LinkedReport[AsgKey, AsgReport]]] =
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

  private def getEbAppReport(name: elasticbeanstalk.model.primitives.ApplicationName): ZQuery[QueryEnv, AwsError, LinkedReport[EbAppKey, EbAppReport]] =
    cached(name)(name => ZIO.succeed(EbAppKey(name))) { (key: EbAppKey) =>
      for {
        app <- ebquery.getApplicationByName(name).someOrFail(AwsError.fromThrowable(new IllegalStateException(s"EB Application not found for EB env")))
        envs <- ebquery.getEnvironmentsByAppName(name)
        envReports <- ZQuery.collectAllPar(envs.map(getEbEnvReport))
        numberOfVersions = app.versionsValue.map(_.length).getOrElse(0)
        region <- ZQuery.fromEffect(parameters[Parameters].map(_.region))
      } yield EbAppReport(
        name,
        region,
        numberOfVersions,
        envReports
      )
    }
}
