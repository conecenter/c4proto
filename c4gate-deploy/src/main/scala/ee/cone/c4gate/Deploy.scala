package ee.cone.c4gate

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4actor.DirInfo

trait DeploymentTask
trait FileTask extends DeploymentTask {
  def to: Path
}
case class MakeDirTask(to: Path) extends FileTask
case class CopyTask(from: Path, to: Path) extends FileTask
case class TextTask(to: Path, content: String) extends FileTask

case class EnvTask(key: String, value: String) extends DeploymentTask
case class DockerfileLineTask(line: String) extends DeploymentTask



//val deployDir = Paths.get("target/c4build")
class PutFilesDeploy(deployDir: Path) {
  private def recycle(source: Path) =
    if(Files.exists(source))
      Files.move(source, source.resolveSibling(Math.random().toString))
  def putFiles(tasks: List[FileTask]): Unit = { //distinct sorted by to
    tasks.distinct.groupBy(_.to).values.map{
      case e :: Nil ⇒ e
    }.toList.sortBy(_.to).foreach{
      case MakeDirTask(to) ⇒ Files.createDirectories(to)
      case TextTask(to,content) ⇒ Files.write(to,content.getBytes(UTF_8))
      case CopyTask(from,to) ⇒ Files.createLink(to, from)
    }
  }
  def commit(): Unit = {
    
  }
  def run(): Unit = {
    recycle(deployDir)
    putFiles(???)
    commit()
  }
}

trait DeploymentTaskFactory {
  def copy(from: String, to: String): List[DeploymentTask]
  def text(to: String, content: List[String]): List[DeploymentTask]
}

class DeploymentTaskFactoryImpl(dirInfo: DirInfo, deployDir: Path) extends DeploymentTaskFactory {
  def copy(fromStr: String, toStr: String): List[DeploymentTask] = {
    val fromRoot = Paths.get(fromStr)
    val toRoot = deployDir.resolve(toStr)
    dirInfo.deepFiles(fromRoot).flatMap{ (from:Path) ⇒
      val to = toRoot.resolve(fromRoot.relativize(from))
      MakeDirTask(to.getParent) :: CopyTask(from, to) :: Nil
    }
  }
  def text(to: String, content: List[String]): List[DeploymentTask] = {
    val path = Paths.get(to)
    MakeDirTask(path.getParent) :: TextTask(path, content.mkString("\n")) :: Nil
  }
}

class ClassPathDeploy(tasks: DeploymentTaskFactory) {
  def getTasks: List[DeploymentTask] = {
    val loader = getClass.getClassLoader.asInstanceOf[URLClassLoader]
    val Jar = """.+/([^/]+\.jar)""".r
    val Our = """(.+(/|-)classes)(|/)""".r
    val fromTo = loader.getURLs.map(_.getFile).toList.map{
      case path@Jar(fn) => (path,fn)
      case Our(chomped, _, dirFix)  => (chomped,"our")
      case path => throw new Exception(s"unsupported classpath ($path)")
    }.map{ case(from,to) ⇒ (from,s"cps/$to") }
    val classPath = fromTo.map{ case(from,to) ⇒ to }.distinct.sorted.mkString(File.pathSeparator)
    val pub = "htdocs/mod"
    EnvTask("CLASSPATH",classPath) ::
      ((pub,pub) :: fromTo).flatMap{ case(from,to) ⇒ tasks.copy(from,s"app/$to") }
  }
}

trait DeploymentTasksApp {
  def deploymentTasks: List[DeploymentTask] = Nil
}

trait ClassPathDeployApp extends DeploymentTasksApp {
  def deploymentTaskFactory: DeploymentTaskFactory
  private lazy val classPathDeploy = new ClassPathDeploy(deploymentTaskFactory)
  override def deploymentTasks: List[DeploymentTask] =
    classPathDeploy.getTasks ::: super.deploymentTasks
}

object YAML {
  trait Value
  case class CString(value: String) extends Value
  case class CMap(value: Map[String,Value]) extends Value
  def indent(ll: List[String]) =
    ll.map(l⇒s"\n${l.replaceAll("\n","  \n")}").mkString
  def toStr: Value ⇒ String = {
    case CString(v) ⇒ s" '$v'" //todo escape
    case CMap(m) ⇒ indent(m.toList.sortBy(_._1).map{ case(k,v) ⇒ s"$k:${toStr(v)}"}) //todo escape key
  }
}

class BuildTrash(tasks: DeploymentTaskFactory, classPathDeploy: ClassPathDeploy) {
  def gateHttpPort = 8067
  def gateSsePort = 8068
  def zookeeperPort = 2181
  def zookeeperHost = "zookeeper"
  def clientBuildDir = "client/build/test"
  def kafkaVersion = "0.10.2.0"
  def kafkaName = s"kafka_2.11-$kafkaVersion"
  def curlTest = "curl http://haproxy/abc"
  def bootstrapServer = "broker:9092"
  def tempDir = "target"
  def dockerBuild = s"$tempDir/c4build"
  def userInContainer = "c4"
  def uidInContainer = 1979
  def inboxConfigureScript = "inbox_configure.pl"
  def developer = Option(System.getenv("USER")).get

  def sy(args: List[String]) = {
    import scala.sys.process._
    println(s"running: ${args.map(l⇒s"'$l'").mkString(" ")}")
    if(args.! != 0) throw new Exception(s"external process failed: $args")
  }
  def syInDir(dir: String, args: List[String]) = {
    import scala.sys.process._
    Files.createDirectories(Paths.get(dir))
    Process(args, new java.io.File(dir)).!!
  }


  def runLine(commands: List[String]) = s"RUN ${commands.mkString(" && ")}"
  def fromJDK(install: List[String]) = List(
    "FROM openjdk:8",
    runLine(List(
      s"useradd --base-dir / --create-home --user-group --uid $uidInContainer --shell /bin/bash $userInContainer",
      "apt-get update",
      s"apt-get install -y lsof telnet"
    ) ::: install ::: List(
      "rm -rf /var/lib/apt/lists/*",
    )),
    s"COPY . /$userInContainer",
    runLine(List(s"chown -R $userInContainer:$userInContainer /$userInContainer"))
  ).map(DockerfileLineTask)
  def db4(ctxName: String): List[DeploymentTask] = List(MakeDirTask(Paths.get(s"$ctxName/db4")))
  def staged(): List[DeploymentTask] =
    classPathDeploy.getTasks.map{
      case EnvTask(k,v) ⇒ DockerfileLineTask(s"ENV $k $v")
      case o ⇒ o
    } ::: db4("app") ::: fromJDK(Nil)



  def prepareBuild(name: String)(body: String⇒List[DeploymentTask]): List[DeploymentTask] = {
    val(lines,taskList) = body(name).partition(_.isInstanceOf[DockerfileLineTask])
    val content = lines.map{ case DockerfileLineTask(l) ⇒ l }
    tasks.text(s"$name/Dockerfile", content) :::
      tasks.text(s"$name/.dockerignore",List(".dockerignore","Dockerfile")) :::
      taskList
  }

  def zooBuildTasks: List[DeploymentTask] = prepareBuild("zoo"){ (ctxName:String) ⇒
    fromJDK(List(
      s"wget http://www-eu.apache.org/dist/kafka/$kafkaVersion/$kafkaName.tgz",
      s"tar -xzf $kafkaName.tgz",
      s"rm $kafkaName.tgz",
      s"mv $kafkaName kafka"
    )) :::
    tasks.text(s"$ctxName/zookeeper.properties",List(
      "dataDir=db4/zookeeper",
      s"clientPort=$zookeeperPort"
    )) :::
    tasks.text(s"$ctxName/server.properties",List(
      s"listeners=PLAINTEXT://$bootstrapServer",
      s"log.dirs=db4/kafka-logs",
      s"zookeeper.connect=$zookeeperHost:$zookeeperPort",
      s"log.cleanup.policy=compact",
      s"log.segment.bytes=104857600", //active segment is not compacting, so we reduce it
      s"log.cleaner.delete.retention.ms=3600000", //1h
      s"log.roll.hours=1", //delete-s will be triggered to remove?
      s"compression.type=uncompressed", //probably better compaction for .state topics
      s"message.max.bytes=3000000" //seems to be compressed
    )) :::
    tasks.copy(inboxConfigureScript,s"$ctxName/$inboxConfigureScript") :::
    db4(ctxName)
  }
  def composerBuildTasks: List[DeploymentTask] = prepareBuild("composer"){ (ctxName:String) ⇒
    val script = "compose.pl"
    tasks.copy(script, s"$ctxName/$script") :::
    List(
      "FROM docker/compose:1.14.0",
      runLine(List("apk add --no-cache perl perl-yaml-xs")), //perl-yaml-syck
      "COPY . /",
      s"""ENTRYPOINT ["perl","$script"]""",
    ).map(DockerfileLineTask)
  }
  def haproxyBuildTasks: List[DeploymentTask] = prepareBuild("haproxy") { (ctxName: String) ⇒
    tasks.text(s"$ctxName/haproxy.cfg",List(
      "defaults",
      "timeout connect 5s",
      "  timeout client  900s",
      "  timeout server  900s",
      "  resolvers docker_resolver",
      "  nameserver dns \"127.0.0.11:53\"",
      "frontend fe",
      "  mode http",
      "  bind :80",
      "acl acl_sse hdr(accept) -i text/event-stream",
      "use_backend be_sse if acl_sse",
      "default_backend be_http",
      "  backend be_http",
      "  mode http",
      s"  server se_http gate:$gateHttpPort check resolvers docker_resolver resolve-prefer ipv4",
      "  backend be_sse",
      "  mode http",
      s"  server se_sse gate:$gateSsePort check resolvers docker_resolver resolve-prefer ipv4"
    )) :::
    List(
      "FROM haproxy:1.7",
      "COPY haproxy.cfg /usr/local/etc/haproxy/haproxy.cfg",
    ).map(DockerfileLineTask)
  }


  def gateBuildTasks = {
    zooBuildTasks ::: composerBuildTasks ::: haproxyBuildTasks
  }








}