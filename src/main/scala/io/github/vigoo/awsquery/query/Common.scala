package io.github.vigoo.awsquery.query

import io.github.vigoo.awsquery.report.{LinkedReport, Report, ReportKey}
import io.github.vigoo.awsquery.report.cache.{ReportCache, storeIfNew}
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.{autoscaling, ec2, elasticbeanstalk, elasticloadbalancing}
import zio.ZIO
import zio.logging.Logging
import zio.query.ZQuery
import zio.query.Described._

trait Common {
  protected def optionally[R, E, A, B](value: Option[A])(f: A => ZQuery[R, E, B]): ZQuery[R, E, Option[B]] =
    value match {
      case Some(value) => f(value).map(Some.apply)
      case None => ZQuery.none
    }

  protected def cached[R <: ReportCache with Logging, A, B <: Report, K <: ReportKey](input: A)(keyFn: A => ZIO[Any, AwsError, K])(query: K => ZQuery[R, AwsError, B]): ZQuery[R, AwsError, LinkedReport[K, B]] =
    for {
      key <- ZQuery.fromEffect(keyFn(input))
      env <- ZQuery.environment[R]
      _ <- storeIfNew(
        key,
        query(key).provide(env ? "provided environment")
      )
    } yield LinkedReport[K, B](key)
}

object Common {
  type AllServices = ec2.Ec2 with elasticloadbalancing.ElasticLoadBalancing with elasticbeanstalk.ElasticBeanstalk with autoscaling.AutoScaling
}