package io.github.vigoo.awsquery.query

import io.github.vigoo.awsquery.query.Common.AllServices
import io.github.vigoo.awsquery.report.{ElbKey, ElbReport, LinkedReport}
import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.awsquery.sources.{ebquery, elbquery}
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticloadbalancing.model.LoadBalancerDescription
import zio.ZIO
import zio.logging.Logging
import zio.query.ZQuery

import scala.util.matching.Regex

trait ElbQuery {
  this: Common with EbQuery =>

  private val cloudFormationStackRegex: Regex = """^awseb-(.*)-stack$""".r

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
