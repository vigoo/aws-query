package io.github.vigoo.awsquery.query

import java.net.URI

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.report.{ElbKey, ElbReport, LinkedReport}
import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.awsquery.sources.{ebquery, elbquery}
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticloadbalancing.model.LoadBalancerDescription
import zio.ZIO
import zio.logging.Logging
import zio.query.ZQuery

import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

trait ElbQuery {
  this: Common with EbQuery =>

  private val cloudFormationStackRegex: Regex = """^awseb-(.*)-stack$""".r
  private val domainRegex: Regex = "^(dualstack\\.)?(.*)-[0-9]*\\.([-a-z0-9]*)\\.elb\\.amazonaws\\.com$".r("dualstack", "name", "region")

  def getElbReportByInput(input: String): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[ElbKey, ElbReport]] = {
    Try(Option(URI.create(input).getAuthority).getOrElse(input)) match {
      case Failure(_) =>
        getElbReportByNameOrInstanceId(input)
      case Success(domain) =>
        domainRegex.findFirstMatchIn(domain) match {
          case Some(m) =>
            elbquery.getLoadBalancer(m.group("name")) >>= getElbReport
          case None =>
            getElbReportByNameOrInstanceId(input)
        }
    }
  }

  private def getElbReportByNameOrInstanceId(input: String): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[ElbKey, ElbReport]] =
    if (input.startsWith("i-")) {
      elbquery
        .loadBalancerOf(input)
        .someOrFail(AwsError.fromThrowable(new IllegalArgumentException(s"could not find ELB containing instance $input"))) >>=
        getElbReport
    } else {
      elbquery.getLoadBalancer(input) >>= getElbReport
    }

  def getElbReport(elb: LoadBalancerDescription.ReadOnly): ZQuery[Logging with ReportCache with AllServices, AwsError, LinkedReport[ElbKey, ElbReport]] =
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

}
