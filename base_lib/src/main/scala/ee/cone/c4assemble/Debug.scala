package ee.cone.c4assemble

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

object DebugCounter {
  val values: IndexedSeq[AtomicLong] = (0 until 20).map(i=>new AtomicLong(0))
  def add(pos: Int, d: Long): Unit = {
    val ignore = values(pos).addAndGet(d)
  }
  def report(): String = values.map(_.get()).mkString(" ")
  def reset(): Unit = for(v <- values) v.set(0)

  private val onV = (0 until 20).map(i=>new AtomicBoolean(false))
  def on(pos: Int): Boolean = onV(pos).get
  def on(pos: Int, value: Boolean): Unit = onV(pos).set(value)
}
