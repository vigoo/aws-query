package io.github.vigoo.awsquery.report

import io.github.vigoo.awsquery.report.cache.ReportCache
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.elasticloadbalancing.model.Listener
import zio._
import zio.console.Console
import zio.prelude._

import scala.io.AnsiColor._

package object render {
  type Rendering = Has[Rendering.Service]

  object Rendering {

    trait Service {
      def renderEc2Instance(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): UIO[Unit]
      def renderElb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): UIO[Unit]
      def renderAsg(report: LinkedReport[AsgKey, AsgReport]): UIO[Unit]
      def renderEbEnv(report: LinkedReport[EbEnvKey, EbEnvReport]): UIO[Unit]
      def renderEbApp(report: LinkedReport[EbAppKey, EbAppReport]): UIO[Unit]
    }

  }

  sealed trait Print[+A]
  case class Pure[A](a: A) extends Print[A]
  case class PrintS(s: String) extends Print[Unit]
  case class PrintModified(s: String, modifiers: String) extends Print[Unit]
  case object PrintNL extends Print[Unit]
  case class PrintIndented[A](p: Print[A]) extends Print[A]
  case class PrintFlatMap[A, B](a: Print[A], f: A => Print[B]) extends Print[B]
  case class PrintEffect[A](f: UIO[A]) extends Print[A]


  object Print {
    implicit val print = new Covariant[Print] with IdentityFlatten[Print] with IdentityBoth[Print] {
      override def map[A, B](f: A => B): Print[A] => Print[B] = fa => PrintFlatMap(fa, (a: A) => Pure(f(a)))

      override def any: Print[Any] = Pure(())
      override def flatten[A](ffa: Print[Print[A]]): Print[A] = PrintFlatMap(ffa, (fa: Print[A]) => fa)
      override def both[A, B](fa: => Print[A], fb: => Print[B]): Print[(A, B)] = PrintFlatMap(fa, (a: A) => map((b: B) => (a, b))(fb))
    }

    val unit: Print[Unit] = Pure(())
    val space: Print[Unit] = PrintS(" ")
    val newLine: Print[Unit] = PrintNL

    def indented[A](inner: Print[A]): Print[A] = PrintIndented(inner)

    def normal(text: String): Print[Unit] = PrintS(text)

    def details(text: String): Print[Unit] = PrintModified(text, CYAN)

    def sectionHeader(text: String): Print[Unit] = PrintS("[") *> PrintModified(text, s"$MAGENTA$BOLD") *> PrintS("]")

    def highlighted(text: String): Print[Unit] = PrintModified(text, s"$GREEN$BOLD")

    def keyword(text: String): Print[Unit] = PrintModified(text, s"$YELLOW$BOLD")

    def link(text: String): Print[Unit] = PrintModified(text, s"$MAGENTA$BOLD$UNDERLINED")

    def error(text: String): Print[Unit] = PrintModified(text, RED)

    def lift[A](f: UIO[A]): Print[A] = PrintEffect(f)

    implicit class PrintOps[A](self: Print[A]) {
      def <->[B](next: => Print[B]): Print[B] =
        self *> space *> next

      def \\(next: => Print[Unit]): Print[Unit] =
        self *> newLine *> next

      def <:>(next: => Print[Unit]): Print[Unit] =
        self *> details(":") *> space *> next
    }

  }

  private trait PrettyConsole {
    protected val console: Console.Service

    private case class PrettyState(indentation: String, afterNL: Boolean)

    private def printFlatMap[A, B](a: Print[A], f: A => Print[B], state: PrettyState): UIO[(B, PrettyState)] =
      for {
        r1 <- runImpl(a, state)
        r2 <- runImpl(f(r1._1), r1._2)
      } yield r2

    private def runImpl[A](p: Print[A], state: PrettyState): UIO[(A, PrettyState)] =
      p match {
        case Pure(a) => ZIO.succeed((a, state))
        case PrintS(s) => ZIO.when(state.afterNL)(console.putStr(state.indentation)) *> console.putStr(s).as(((), state.copy(afterNL = false)))
        case PrintModified(s, modifiers) => ZIO.when(state.afterNL)(console.putStr(state.indentation)) *> console.putStr(s"${modifiers}$s$RESET").as(((), state.copy(afterNL = false)))
        case PrintNL => if (state.afterNL) ZIO.succeed(((), state)) else console.putStrLn("").as(((), state.copy(afterNL = true)))
        case PrintIndented(f) => runImpl(f, state.copy(indentation = state.indentation + "  ")).map { case (a, s) => (a, s.copy(indentation = state.indentation)) }
        case PrintFlatMap(a, f) => printFlatMap(a, f, state)
        case PrintEffect(f) => f.map((_, state))
      }

    def run[A](p: Print[A]): UIO[A] = runImpl(p, PrettyState("", afterNL = false)).map(_._1)
  }

  private class PrettyConsoleRendering(state: Ref[State],
                                       override protected val console: Console.Service,
                                       reportCache: ReportCache.Service)
    extends Rendering.Service with PrettyConsole {

    import Print._

    private def keyValueList(items: Map[String, String]): Print[Unit] =
      items.toList.foreach_ { case (key, value) =>
        keyword(key) *> details(":") <-> normal(value) *> newLine
      }

    private def ifDefined[A](value: Option[A])(f: A => Print[Unit]): Print[Unit] =
      value match {
        case Some(value) =>
          f(value)
        case None =>
          unit
      }

    private def ifNotVisitedYet[K <: ReportKey, R <: Report](link: LinkedReport[K, R])(f: R => Print[Unit]): Print[Unit] =
      for {
        st <- lift(state.get)
        _ <-
          if (st.alreadyVisited(link.key)) {
            unit
          } else {
            for {
              reportOrFailure <- lift(reportCache
                .retrieve[R](link.key)
                .either)
              _ <- lift(state.set(st.copy(alreadyVisited = st.alreadyVisited + link.key)))
              _ <- reportOrFailure match {
                case Left(failure) => error(failure.toString)
                case Right(None) => error(s"Not found: ${link.key}")
                case Right(Some(report)) => f(report)
              }
            } yield ()
          }
      } yield ()

    private def listenerList(value: List[Listener.ReadOnly]): Print[Unit] =
      value.foreach_ { listener =>
        keyword(listener.protocolValue) *> details(s"(${listener.loadBalancerPortValue})") <->
          (listener.instanceProtocolValue match {
            case None => unit
            case Some(protocol) => normal("->") <-> keyword(protocol) *> details(s"(${listener.instancePortValue})")
          }) <-> ifDefined(listener.sslCertificateIdValue) { id => details(s"with SSL certificate $id")}
      }

    private def elb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): Print[Unit] =
      ifNotVisitedYet(report) { elb =>
        sectionHeader("ELB") <-> highlighted(elb.name) <-> ifDefined(context)(details) \\
          indented {
            keyword("AWS Console") <:> link(s"https://console.aws.amazon.com/ec2/v2/home?region=${elb.region}#LoadBalancers:search=${elb.name}") \\
            keyword("Tags") <:> newLine *> indented(keyValueList(elb.tags)) *>
            keyword("Listener") <:> listenerList(elb.listeners) \\
            keyword(s"${elb.availabilityZones.size} availability zones") <:> details(elb.availabilityZones.mkString(" ")) \\
            keyword(s"${elb.instancesIds.size} instances") <:> details(elb.instancesIds.mkString(" ")) \\
            ifDefined(elb.ebEnv)(ebEnv)
          }
      }

    private def ec2(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): Print[Unit] =
      ifNotVisitedYet(report) { ec2 =>
        sectionHeader("EC2/Instance") <-> highlighted(ec2.instanceId) <-> normal("is an EC2 instance in") <-> normal(List(Some(ec2.region), ec2.vpcId, ec2.subnetId).flatten.mkString(" / ")) \\
          indented {
            keyword("AWS Console") <:> link(s"https://console.aws.amazon.com/ec2/v2/home?region=${ec2.region}#Instances:search=${ec2.instanceId}") \\
            keyword("State") <:> highlighted(ec2.state.toString) \\
            keyword("Tags") <:> newLine *> indented(keyValueList(ec2.tags)) *>
            ifDefined(ec2.publicIp) { ip => keyword("Public IP") <:> normal(ip) <-> ifDefined(ec2.publicDns)(details) *> newLine } *>
            ifDefined(ec2.privateIp) { ip => keyword("Private IP") <:> normal(ip) <-> ifDefined(ec2.privateDns)(details) *> newLine } *>
            keyword("Instance type") <:> normal(ec2.instanceType.toString) \\
            keyword("Security groups") <:> ec2.securityGroups.toList.foreach_ { case (sgName, sgId) => normal(sgName) <-> details(s"($sgId)") *> space } \\
            keyword("AMI") <:> normal(ec2.amiName) <-> details(s"(${ec2.amiId})") \\
            keyword("Instance profile") <:> normal(ec2.instanceProfileArn) <-> details(s"(${ec2.instanceProfileId})") \\
            keyword("SSH key name") <:> normal(ec2.sshKeyName) \\
            keyword("Launched at") <:> normal(ec2.launchedAt.toString) \\
            ifDefined(ec2.elb)(elb(_, Some(s"the instance ${ec2.instanceId} is registered into this ELB ")))
          }
      }

    private def ebEnv(report: LinkedReport[EbEnvKey, EbEnvReport]): Print[Unit] =
      ifNotVisitedYet(report) { env =>
        sectionHeader("Beanstalk/Env") <-> highlighted(env.name) <-> details(env.id) <-> normal(s"is a Beanstalk environment of the application ${env.appName}") \\
          indented {
            keyword("AWS Console") <:> link(s"https://console.aws.amazon.com/elasticbeanstalk/home?region=${env.region}#/environment/dashboard?applicationName=${env.appName}&environmentId=${env.id}") \\
            keyword("Health") <:> highlighted(env.health.toString) \\
            keyword("Currently running version") <:> normal(env.version) \\
            normal(s"${env.asgs.size} ASGs, ${env.instanceCount} instances, ${env.elbs.size} ELBs") \\
            env.elbs.foreach_(elb(_, None)) \\
            env.asgs.foreach_(asg) \\
            ebApp(env.app)
          }
      }

    private def ebApp(report: LinkedReport[EbAppKey, EbAppReport]): Print[Unit] =
      ifNotVisitedYet(report) { app =>
        sectionHeader("Beanstalk/App") <-> normal("Application") <-> highlighted(app.name) \\
        indented {
          keyword("AWS Console") <:> link(s"https://console.aws.amazon.com/elasticbeanstalk/home?region=${app.region}#/application/overview?applicationName=${app.name}") \\
          normal(s"${app.numberOfVersions} versions") \\
          app.ebEnvs.foreach_(ebEnv)
        }
      }

    private def asg(report: LinkedReport[AsgKey, AsgReport]): Print[Unit] =
      ifNotVisitedYet(report) { asg =>
        sectionHeader("EC2/ASG") <-> normal("Auto Scaling group") <-> highlighted(asg.id) <-> normal(s"in ${asg.region}") \\
          indented {
            keyword("AWS Console") <:> link(s"https://console.aws.amazon.com/ec2/autoscaling/home?region=${asg.region}#AutoScalingGroups:id=${asg.id};filter=${asg.id};view=details") \\
            keyword("Instances") <:> normal(s"current=${asg.instanceCount} min=${asg.minSize} max=${asg.maxSize} desired=${asg.desiredCapacity}")
            keyword("Tags") <:> newLine *> indented(keyValueList(asg.tags)) *>
            keyword("Created at") <:> normal(asg.createdAt.toString) \\
            asg.loadBalancers.foreach_(elb(_, None)) \\
            ifDefined(asg.ebEnv)(ebEnv) \\
            launchConfig(asg.launchConfiguration)
          }
      }

    private def launchConfig(report: LinkedReport[LaunchConfigKey, LaunchConfigReport]): Print[Unit] =
      ifNotVisitedYet(report) { lc =>
        sectionHeader("EC2/LaunchConfiguration") <-> highlighted(lc.name) <-> normal(s"in ${lc.region}") \\
        indented {
          keyword("Created at") <:> normal(lc.createdAt.toString) \\
          keyword("AMI") <:> normal(lc.amiId) \\
          ifDefined(lc.instanceProfileArn)(arn => keyword("IAM Instance Profile") <:> details(arn)) \\
          keyword("Instance type") <:> normal(lc.instanceType) \\
          keyword("Security groups") <:> details(lc.securityGroups.mkString(" "))
        }
      }

    override def renderElb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): UIO[Unit] =
      run(elb(report, context))

    override def renderEc2Instance(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): UIO[Unit] =
      run(ec2(report))

    override def renderAsg(report: LinkedReport[AsgKey, AsgReport]): UIO[Unit] =
      run(asg(report))

    override def renderEbEnv(report: LinkedReport[EbEnvKey, EbEnvReport]): UIO[Unit] =
      run(ebEnv(report))

    override def renderEbApp(report: LinkedReport[EbAppKey, EbAppReport]): UIO[Unit] =
      run(ebApp(report))
  }

  private case class State(alreadyVisited: Set[ReportKey])

  val live: ZLayer[Console with ReportCache, Nothing, Rendering] = ZLayer.fromServicesM[Console.Service, ReportCache.Service, Any, Nothing, Rendering.Service] {
    (console, reportCache) =>
      for {
        alreadyVisited <- Ref.make(State(Set.empty))
      } yield new PrettyConsoleRendering(alreadyVisited, console, reportCache)
  }

  def renderEc2Instance(report: LinkedReport[Ec2InstanceKey, Ec2InstanceReport]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderEc2Instance(report))

  def renderElb(report: LinkedReport[ElbKey, ElbReport], context: Option[String]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderElb(report, context))

  def renderAsg(report: LinkedReport[AsgKey, AsgReport]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderAsg(report))

  def renderEbEnv(report: LinkedReport[EbEnvKey, EbEnvReport]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderEbEnv(report))

  def renderEbApp(report: LinkedReport[EbAppKey, EbAppReport]): ZIO[Rendering, Nothing, Unit] =
    ZIO.accessM(_.get.renderEbApp(report))
}
