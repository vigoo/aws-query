package io.github.vigoo.awsquery.report

import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.zioaws.core.AwsError
import zio._
import zio.console.Console

import scala.io.AnsiColor._

package object render {
  type Rendering = Has[Rendering.Service]

  object Rendering {

    trait Service {
      def renderEc2Instance(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): UIO[Unit]
      def renderElb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): UIO[Unit]
    }

  }

  private trait PrettyConsole {
    protected val console: Console.Service

    protected def newLine: UIO[Unit] = console.putStrLn("")
    protected def space: UIO[Unit] = console.putStr(" ")

    protected def normal(text: String): UIO[Unit] = console.putStr(text)

    protected def sectionHeader(text: String): UIO[Unit] = console.putStr(s"$BLACK_B[$MAGENTA$text$BLACK_B]$RESET")

    protected def highlighted(text: String): UIO[Unit] = console.putStr(s"$GREEN_B$text$RESET")

    protected def keyword(text: String): UIO[Unit] = console.putStr(s"$BLUE_B$text$RESET")

    protected def details(text: String): UIO[Unit] = console.putStr(s"$BLACK_B$text$RESET")

    protected def link(text: String): UIO[Unit] = console.putStr(s"$MAGENTA_B$text$RESET")

    protected implicit class Syntax(op: UIO[Unit]) {
      def <->(next: UIO[Unit]): UIO[Unit] =
        op *> space *> next

      def \\(next: UIO[Unit]): UIO[Unit] =
        op *> newLine *> next

      def <:>(next: UIO[Unit]): UIO[Unit] =
        op *> details(":") *> space *> next
    }

  }

  private class PrettyConsoleRendering(state: Ref[State],
                                       override protected val console: Console.Service,
                                       reportCache: ReportCache.Service)
    extends Rendering.Service with PrettyConsole {

    private def id: UIO[Unit] =
      state.get.flatMap(state => console.putStr(state.indentation))

    private def indented(f: UIO[Unit]): UIO[Unit] =
      state.get.flatMap { st =>
        for {
          _ <- state.set(st.copy(indentation = st.indentation + "  "))
          _ <- f
          _ <- state.set(st)
        } yield ()
      }

    private def keyValueList(items: Map[String, String]): UIO[Unit] =
      ZIO.foreach_(items) { case (key, value) =>
        id *> keyword(key) *> details(":") <-> normal(value) *> newLine
      }

    private def ifDefined[A](value: Option[A])(f: A => UIO[Unit]): UIO[Unit] =
      value match {
        case Some(value) =>
          f(value)
        case None =>
          ZIO.unit
      }

    private def ifNotVisitedYet[K <: ReportKey, R <: Report](link: LinkedReport[K, R])(f: R => UIO[Unit]): UIO[Unit] =
      state.get.flatMap { st =>
        (for {
          report <- reportCache
            .retrieve[R](link.key)
            .someOrFail(AwsError.fromThrowable(new IllegalStateException(s"${link.key} not found")))
          _ <- state.set(st.copy(alreadyVisited = st.alreadyVisited + link.key))
          _ <- f(report)
        } yield ())
          .catchAll { error =>
            console.putStrLnErr(s"Failed to retrieve cached report: $error")
          }
          .unless(st.alreadyVisited.contains(link.key))
      }

    override def renderElb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): UIO[Unit] = {
      ifNotVisitedYet(report) { elb =>
        id *> sectionHeader("ELB") <-> highlighted(elb.name) <-> ifDefined(context)(details) *> newLine
      }
    }

    override def renderEc2Instance(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): UIO[Unit] = {
      ifNotVisitedYet(report) { ec2 =>
        id *> sectionHeader("EC2/Instance") <-> highlighted(ec2.instanceId) <-> normal("is an EC2 instance in") *> normal(List(Some(ec2.region), ec2.vpcId, ec2.subnetId).flatten.mkString(" / ")) \\
        indented {
          id *> keyword("AWS Console") <:> link(s"https://console.aws.amazon.com/ec2/v2/home?region=${ec2.region}#Instances:search=${ec2.instanceId}") \\
          id *> keyword("State") <:> highlighted(ec2.state.toString) \\
          id *> keyword("Tags") <:> newLine *> indented(keyValueList(ec2.tags)) *>
          ifDefined(ec2.publicIp) { ip => id *> keyword("Public IP") <:> normal(ip) <-> ifDefined(ec2.publicDns)(details) *> newLine } *>
          ifDefined(ec2.privateIp) { ip => id *> keyword("Private IP") <:> normal(ip) <-> ifDefined(ec2.privateDns)(details) *> newLine } *>
          id *> keyword("Instance type") <:> normal(ec2.instanceType.toString) \\
          id *> keyword("Security groups") <:> ZIO.foreach_(ec2.securityGroups) { case (sgName, sgId) => normal(sgName) <-> details(s"($sgId)") } \\
          id *> keyword("AMI") <:> normal(ec2.amiName) <-> details(ec2.amiId) \\
          id *> keyword("Instance profile") <:> normal(ec2.instanceProfileArn) <-> details(ec2.instanceProfileId) \\
          id *> keyword("SSH key name") <:> normal(ec2.sshKeyName) \\
          id *> keyword("Launched at") <:> normal(ec2.launchedAt.toString) \\
          ifDefined(ec2.elb) { elb => indented { renderElb(elb, Some(s"the instance ${ec2.instanceId} is registered into this ELB ")) } *> newLine }
        }
      }
    }
  }

  private case class State(alreadyVisited: Set[ReportKey], indentation: String)

  val live: ZLayer[Console with ReportCache, Nothing, Rendering] = ZLayer.fromServicesM[Console.Service, ReportCache.Service, Any, Nothing, Rendering.Service] {
    (console, reportCache) =>
      for {
        alreadyVisited <- Ref.make(State(Set.empty, ""))
      } yield new PrettyConsoleRendering(alreadyVisited, console, reportCache)
  }

  def renderEc2Instance(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderEc2Instance(report))
  def renderElb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderElb(report, context))
}
