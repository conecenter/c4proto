package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.AssembleProfiler

object SimpleAssembleProfiler extends AssembleProfiler with LazyLogging {
  def get(ruleName: String): String ⇒ Int ⇒ Unit = startAction ⇒ {
    val startTime = System.currentTimeMillis
    finalCount ⇒ {
      val period = System.currentTimeMillis - startTime
      logger.trace(s"assembling by ${Thread.currentThread.getName} rule $ruleName $startAction $finalCount items in $period ms")
    }
  }
}
