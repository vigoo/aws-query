package io.github.vigoo.awsquery.sources

import zio.{Chunk, ZIO}
import zio.logging.{Logging, log}
import zio.query.{CompletedRequestMap, DataSource, Request}

object AwsDataSource {

  implicit class DataSourceImplSyntax[R <: Logging, AwsError](f: ZIO[R, AwsError, CompletedRequestMap]) {
    def recordFailures[A](description: String, requests: Iterable[Request[AwsError, A]]): ZIO[R, Nothing, CompletedRequestMap] =
      f.catchAll { error =>
        log.error(s"$description failed with $error") *>
          ZIO.succeed {
            requests.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
              resultMap.insert(req)(Left(error))
            }
          }
      }
  }
}
