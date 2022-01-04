package ee.cone.c4actor

import java.nio.file.Path
import java.util.concurrent.ExecutorService

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

trait Execution extends Runnable {
  def onShutdown(hint: String, f:()=>Unit): ()=>Unit
  def complete(): Unit
  def skippingFuture[T](value: T): SkippingFuture[T]
  def newExecutorService(prefix: String, threadCount: Option[Int]): ExecutorService
  def fatal[T](future: ExecutionContext=>Future[T]): Unit
  def mainExecutionContext: ExecutionContext
  def unboundedFatal[T](future: ExecutionContext=>Future[T]): Future[T]
  def success[T](promise: Promise[T], value: T): Unit
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

// mark Executable with Early
//   if it is highly optimal to start it before the world is ready;
// Early Executable SHOULD NOT write to anything (kafka,db,jms)
//   because another instance of the same app may be still alive;
trait Early
abstract class ExecutionFilter(val check: Executable=>Boolean)

trait Config {
  def get(key: String): String
}
trait ListConfig {
  def get(key: String): List[String]
}


case class ActorName(value: String, prefix: String)

trait SimpleSigner extends Signer[List[String]]
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
