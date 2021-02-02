package ee.cone.c4ui

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{ActorName, Config, Executable, Execution}
import ee.cone.c4di._

import scala.annotation.tailrec

@c4("TestTodoApp") final class FailOverTest(
  config: Config,
  actorName: ActorName,
  execution: Execution,
) extends Executable with LazyLogging {
  def run(): Unit = concurrent.blocking {
    val remove = execution.onShutdown("test-fail-over",()=>{
      iter(true)
    })
    iter(false)
  }
  @tailrec def iter(isFinal: Boolean): Unit = {
    logger.debug(s"normal ${actorName.value} ${config.get("C4ELECTOR_SERVERS")}")
    Thread.sleep(100)
    iter(isFinal)
  }
}
