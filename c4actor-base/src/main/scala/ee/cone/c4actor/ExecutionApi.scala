package ee.cone.c4actor

import java.util.concurrent.ExecutorService

import ee.cone.c4proto.AbstractComponents

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait Execution extends Runnable {
  def onShutdown(hint: String, f:()=>Unit): ()=>Unit
  def complete(): Unit
  def skippingFuture[T](value: T): SkippingFuture[T]
  def newExecutorService(prefix: String, threadCount: Option[Int]): ExecutorService
  def fatal[T](future: ExecutionContext=>Future[T]): Unit
}

trait SkippingFuture[T] {
  def map(body: T => T): SkippingFuture[T]
  def value: Option[Try[T]]
}

trait ExecutableApp {
  def execution: Runnable // we need this while we have componentRegistry.resolve to avoid 2 componentRegistry-s
}
/* target (w/o resolve):
object ExecutionRun {
  def apply(app: AbstractComponents): Unit = ComponentRegistry(app).resolveSingle(classOf[Execution])
}*/

trait Executable extends Runnable

trait Config {
  def get(key: String): String
  def getOpt(key: String): Option[String]
}
trait ListConfig {
  def get(key: String): List[String]
}


case class ActorName(value: String)

trait Signer[T] {
  def sign(data: T, until: Long): String
  def retrieve(check: Boolean): Option[String]=>Option[T]
}


////

object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable =>
      System.err.println(s"TRACED: ${e.getMessage}")
      e.printStackTrace()
      throw e
  }
}

object FinallyClose {
  def apply[A<:AutoCloseable,R](o: A)(f: A=>R): R = try f(o) finally o.close()
  def apply[A,R](close: A=>Unit)(o: A)(f: A=>R): R = try f(o) finally close(o)
}

trait CatchNonFatal {
  def apply[T](aTry: =>T)(hint: =>String)(aCatch: Throwable=>T): T
}

case class NanoTimer(startedAt: Long = System.nanoTime){
  def ms: Long = (System.nanoTime - startedAt) / 1000000
}