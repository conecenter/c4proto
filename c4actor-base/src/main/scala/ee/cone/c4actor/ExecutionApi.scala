package ee.cone.c4actor

import java.util.concurrent.{CompletableFuture, ExecutorService}

trait ToStartApp {
  def toStart: List[Executable] = Nil
}

trait Executable {
  def run(ctx: ExecutionContext): Unit
}

class ExecutionContext(
    val executors: ExecutorService,
    val onShutdown: (()⇒Unit)⇒Unit,
    val serving: CompletableFuture[Unit]
)

trait Config {
  def get(key: String): String
}

////

object Trace { //m. b. to util
  def apply[T](f: =>T): T = try { f } catch {
    case e: Throwable => e.printStackTrace(); throw e
  }
}
