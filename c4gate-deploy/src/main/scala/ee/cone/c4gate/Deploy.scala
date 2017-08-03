package ee.cone.c4gate

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets.UTF_8

trait DeploymentTask
trait PutFileTask extends DeploymentTask {
  def to: String
}
case class CopyTask(from: String, to: String) extends PutFileTask
case class TextTask(to: String, content: String) extends PutFileTask
case class EnvTask(key: String, value: String) extends DeploymentTask

/*
getTasks
tasks to file-tasks -- Dockerfile, yml
+putFiles
commit -- run compose
 */


class PutFilesDeploy {
  private def recycle(source: Path) =
    if(Files.exists(source))
      Files.move(source, source.resolveSibling(Math.random().toString))
  def putFiles(tasks: List[PutFileTask]): Unit = { //distinct
    val deployDir = Paths.get("target/c4build")
    recycle(deployDir)
    tasks.groupBy(_.to).values
      .foreach(tasks⇒if(tasks.size>1) throw new Exception(tasks.toString))
    tasks.map(_.to).map(Paths.get(_).getParent).distinct
      .foreach(Files.createDirectories(_))
    tasks.foreach{
      case TextTask(to,content) ⇒ Files.write(Paths.get(to),content.getBytes(UTF_8))
      case CopyTask(from,to) ⇒ Files.createLink(Paths.get(to), Paths.get(from))
    }
  }
}

trait DeploymentTaskFactory {
  def copy(from: String, to: String): List[DeploymentTask]
  def text(to: String, content: String): DeploymentTask
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

