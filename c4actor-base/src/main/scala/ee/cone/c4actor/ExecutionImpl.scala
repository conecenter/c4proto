
package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try

class VMExecution(getToStart: ()⇒List[Executable]) extends Execution with LazyLogging {
  def run(): Unit = {
    val toStart = getToStart()
    logger.debug(s"tracking ${toStart.size} services")
    toStart.foreach(f ⇒ future(()).map(_⇒f.run()))
  }
  def onShutdown(hint: String, f: () ⇒ Unit): ()⇒Unit = {
    val thread = new Thread(){
      override def run(): Unit = {
        //println(s"hook-in $hint")
        f()
        //println(s"hook-out $hint")
      }
    }
    Runtime.getRuntime.addShutdownHook(thread)
    () ⇒ try {
      Runtime.getRuntime.removeShutdownHook(thread)
    } catch {
      case e: IllegalStateException ⇒ ()
    }
  }

  def complete(): Unit = { // exit from pooled thread will block itself
    logger.info("exiting")
    System.exit(0)
  }
  def future[T](value: T): FatalFuture[T] =
    new VMFatalFuture(Future.successful(value))
}

class VMFatalFuture[T](val inner: Future[T]) extends FatalFuture[T] with LazyLogging {
  def map(body: T ⇒ T): FatalFuture[T] =
    new VMFatalFuture(inner.map(from ⇒ try body(from) catch {
      case e: Throwable ⇒
        logger.error("fatal",e)
        System.exit(1)
        throw e
    }))
  def value: Option[Try[T]] = inner.value
}

abstract class BaseServerMain(app: ExecutableApp){
  def main(args: Array[String]): Unit = try {
    Trace {
      app.execution.run()
      //println("main is about to sleep")
      Thread.sleep(Long.MaxValue) //ctx.serving.get()
    }
  } finally System.exit(0)
}

object ServerMain extends BaseServerMain(
  Option(Class.forName((new EnvConfigImpl).get("C4STATE_TOPIC_PREFIX"))).get
    .newInstance().asInstanceOf[ExecutableApp]
)

class EnvConfigImpl extends Config {
  def get(key: String): String =
    Option(System.getenv(key)).getOrElse(throw new Exception(s"Need ENV: $key"))
}

class FileAuthKey(fileName: String, idGenUtil : IdGenUtil)(
  val value: String = new String(Files.readAllBytes(Paths.get(fileName)),UTF_8)
) extends AuthKey {
  /**
    * Creates hash from input string using value
    */
  def createHash(addInfo: String): String = idGenUtil.srcIdFromStrings(addInfo, value)

  /**
    * Checks if given hash is correct for shouldAddInfo
    */
  def checkHash(shouldAddInfo: String): String => Boolean =
    createHash(shouldAddInfo) == _
}