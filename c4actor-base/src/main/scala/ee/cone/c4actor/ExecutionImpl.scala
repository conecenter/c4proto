
package ee.cone.c4actor

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class VMExecution(getToStart: ()⇒List[Executable]) extends Execution {
  def run(): Unit = {
    val toStart = getToStart()
    println(s"tracking ${toStart.size} services")
    toStart.foreach(f ⇒ future().map(_⇒f.run()))
  }
  def onShutdown(hint: String, f: () ⇒ Unit): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(){
      override def run(): Unit = {
        //println(s"hook-in $hint")
        f()
        //println(s"hook-out $hint")
      }
    })
  def complete(): Unit = { // exit from pooled thread will block itself
    println("exiting")
    System.exit(0)
  }
  def future[T](value: T): FatalFuture[T] =
    new VMFatalFuture(Future.successful(value))
}

class VMFatalFuture[T](val value: Future[T]) extends FatalFuture[T] {
  def map(body: T ⇒ T): FatalFuture[T] =
    new VMFatalFuture(value.map(from ⇒ try body(from) catch {
      case e: Throwable ⇒
        e.printStackTrace()
        System.exit(1)
        throw e
    }))
  def isCompleted: Boolean = value.isCompleted
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

