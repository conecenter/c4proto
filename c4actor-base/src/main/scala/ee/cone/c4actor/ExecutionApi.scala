package ee.cone.c4actor

import java.util.concurrent.{ExecutorService}

trait Executable {
  def run(ctx: ExecutionContext): Unit
}

class ExecutionContext(
    val executors: ExecutorService,
    val onShutdown: (String,()⇒Unit)⇒Unit,
    val complete: Option[Throwable] ⇒ Unit
)

trait Config {
  def get(key: String): String
}

////

object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable =>
      println("Throwable1")
      e.printStackTrace();
      throw e
  }
}

object FinallyClose {
  def apply[A<:AutoCloseable,T](o: A)(f: A⇒T): T = try f(o) finally o.close()
}