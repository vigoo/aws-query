package io.github.vigoo.awsquery.sources

import io.github.vigoo.zioaws.core.AwsError
import zio.logging._
import zio.logging.LogAnnotation._
import zio.query.{CompletedRequestMap, DataSource, Request}
import zio.stream.ZStream
import zio.{Chunk, ZIO}

import scala.reflect.ClassTag

trait AllOrPerItem[R, Req, Item] {
  val name: String

  def isGetAll(request: Req): Boolean

  def isPerItem(request: Req): Boolean

  val allReq: Req

  def itemToReq(item: Item): ZIO[R, AwsError, Req]

  def getAll(): ZStream[R, AwsError, Item]

  def getSome(reqs: Set[Req]): ZStream[R, AwsError, Item]

  def processAdditionalRequests(requests: Chunk[Req], partialResult: CompletedRequestMap): ZIO[R, Nothing, CompletedRequestMap] =
    ZIO.succeed(partialResult)
}

object AllOrPerItem {

  def make[R <: Logging, Req <: Request[AwsError, Any], Item](definition: AllOrPerItem[R, Req, Item])(implicit reqTag: ClassTag[Req]): DataSource[R, Req] =
    DataSource.Batched.make(definition.name) { (requests: Chunk[Req]) =>
      log.locally(Name(definition.name :: Nil)) {
        val containsAll = requests.exists(definition.isGetAll)
        val byName = requests.filter(definition.isPerItem)

        val baseMap = if (containsAll) {
          for {
            _ <- log.info(s"${definition.name} get all")
            foldResult <- definition
              .getAll()
              .foldM((CompletedRequestMap.empty, Set.empty[Item])) { case ((resultMap, all), item) =>
                for {
                  req <- definition.itemToReq(item)
                } yield (resultMap.insert(req)(Right(item)), all + item)
              }
            (perItemMap, allItems) = foldResult
            resultMap = perItemMap.insert(definition.allReq)(Right(allItems))
            _ <- log.info(s"${definition.name} get all completed with ${resultMap.requests.size} items")
          } yield resultMap
        } else {
          ZIO.succeed(CompletedRequestMap.empty)
        }

        baseMap
          .catchAll { error =>
            log.error(s"${definition.name} get all failed with $error") *>
              ZIO.succeed(
                requests.foldLeft(CompletedRequestMap.empty) { case (resultMap, req) =>
                  resultMap.insert(req)(Left(error))
                }
              )
          }
          .flatMap { resultMap =>
            val alreadyHave = resultMap.requests.collect {
              case r: Req if definition.isPerItem(r) => r
            }
            val missing = byName.toSet diff alreadyHave

            val partialResult =
              if (missing.nonEmpty) {
                for {
                  _ <- log.info(s"${definition.name} get (${missing.mkString(", ")})")
                  result <- definition.getSome(missing)
                    .foldM(resultMap) { (resultMap, item) =>
                      for {
                        req <- definition.itemToReq(item)
                      } yield resultMap.insert(req)(Right(item))
                    }
                    .catchAll { error =>
                      log.error(s"${definition.name} get failed with $error") *>
                        ZIO.succeed(
                          missing.foldLeft(resultMap) { case (resultMap, req) =>
                            resultMap.insert(req)(Left(error))
                          })
                    }
                  _ <- log.info(s"${definition.name} get finished with ${result.requests.size} items")
                } yield result
              } else {
                ZIO.succeed(resultMap)
              }

            partialResult.flatMap(definition.processAdditionalRequests(requests, _))
          }
      }
    }
}