package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset
import okio.ByteString

object CommittedConsumer {
  private val header: RawHeader = RawHeader("c","rc")
  def partition(events: Seq[RawEvent]): (Seq[RawEvent], Seq[RawEvent]) = {
    val resolutions = events.flatMap(ev =>
      if(!ev.headers.contains(header)) Nil
      else Seq(ev.srcId->"r") ++ ev.data.utf8().split(":").grouped(2).map{ case Array(txn,res) => (txn,res) }
    ).toMap
    val (resolvedEvents, unresolvedEvents) = events.partition(ev => resolutions.contains(ev.srcId))
    (resolvedEvents.map(ev => resolutions(ev.srcId) match {
      case "c" => ev case "r" => SimpleRawEvent(ev.srcId, ByteString.EMPTY, Nil)
    }), unresolvedEvents)
  }
}

final class CommittedConsumer(inner: Consumer) extends Consumer {
  private var keepEvents = Seq.empty[RawEvent]
  def poll(): List[RawEvent] = {
    val (resolvedEvents, unresolvedEvents) = CommittedConsumer.partition(keepEvents ++ inner.poll())
    keepEvents = unresolvedEvents
    resolvedEvents.toList
  }
  def endOffset: NextOffset = inner.endOffset
}
