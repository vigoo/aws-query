package io.github.vigoo.awsquery.report

import io.github.vigoo.zioaws.core.AwsError
import zio.{Has, Promise, ZIO, ZLayer}
import zio.query.ZQuery
import zio.stm.{TMap, ZSTM}

package object cache {
  type ReportCache = Has[ReportCache.Service]

  object ReportCache {
    trait Service {
      def storeIfNew[A <: Report](reportKey: ReportKey, query: ZQuery[Any, AwsError, A]): ZQuery[Any, AwsError, Boolean]
      def retrieve[A <: Report](key: ReportKey): ZIO[Any, AwsError, Option[A]]
    }
  }

  val live: ZLayer[Any, Nothing, ReportCache] = {
    for {
      cache <- TMap.empty[ReportKey, Promise[AwsError, Report]].commit
    } yield new ReportCache.Service {
      override def storeIfNew[A <: Report](reportKey: ReportKey, query: ZQuery[Any, AwsError, A]): ZQuery[Any, AwsError, Boolean] =
        ZQuery.fromEffect {
          for {
            promise <- Promise.make[AwsError, Report]
            finalQuery <- cache.get(reportKey).flatMap {
              case Some(report) =>
                // replacing the query with the cached value
                ZSTM.succeed(ZQuery.succeed(false))
              case None =>
                // replacing the query with the cached value
                cache.put(reportKey, promise).map { _ =>
                  query.foldM(
                    failure => ZQuery.fromEffect(promise.fail(failure)) *> ZQuery.fail(failure),
                    success => ZQuery.fromEffect(promise.succeed(success))
                  )
                }
            }.commit
          } yield finalQuery
        }.flatMap(identity)

      override def retrieve[A <: Report](key: ReportKey): ZIO[Any, AwsError, Option[A]] =
        for {
          reportPromise <- cache.get(key).commit
          result <- reportPromise match {
            case None => ZIO.none
            case Some(promise) => promise.await.map(r => Some(r.asInstanceOf[A]))
          }
        } yield result
    }
  }.toLayer

  def storeIfNew[A <: Report](key: ReportKey, query: ZQuery[Any, AwsError, A]): ZQuery[ReportCache, AwsError, Boolean] = ZQuery.accessM(_.get.storeIfNew(key, query))

  def retrieve[A <: Report](key: ReportKey): ZIO[ReportCache, AwsError, Option[A]] = ZIO.accessM(_.get.retrieve(key))
}
