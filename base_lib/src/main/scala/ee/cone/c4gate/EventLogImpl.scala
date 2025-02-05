package ee.cone.c4gate

import ee.cone.c4actor.Types.LEvents
import ee.cone.c4actor.{AssembledContext, GetByPK, IdGenUtil, LEvent}
import ee.cone.c4di.c4
import ee.cone.c4proto.{Id, protocol}

@protocol("EventLogApp") object EventLogProtocol {
  @Id(0x0021) case class S_PubLogStart(
    @Id(0x0020) logKey: String,
    @Id(0x0028) nextOffset: Long
  )
  @Id(0x0022) case class S_PubLogEnd(
    @Id(0x0020) logKey: String,
    @Id(0x0028) nextOffset: Long
  )
  @Id(0x0023) case class S_PubEvent(
    @Id(0x0026) eventKey: String,
    @Id(0x0025) value: String
  )
  @Id(0x0024) case class S_PubSnapshot(
    @Id(0x0020) logKey: String,
    @Id(0x0025) value: String,
    @Id(0x0029) readNextOffset: Long
  )
}
import EventLogProtocol._

@c4("EventLogApp") final class EventLogUtilImpl(
  getPubLogStart: GetByPK[S_PubLogStart],
  getPubLogEnd: GetByPK[S_PubLogEnd],
  getPubEvent: GetByPK[S_PubEvent],
  getPubSnapshot: GetByPK[S_PubSnapshot],
  idGenUtil: IdGenUtil,
) extends EventLogUtil {
  private def toEventKey(logKey: String, pos: Long): String = s"$logKey/$pos"
  private def range(world: AssembledContext, logKey: String): (Long, Long, Long => S_PubEvent) = (
    getPubLogStart.ofA(world)(logKey).nextOffset, getPubLogEnd.ofA(world)(logKey).nextOffset,
    (n: Long) => getPubEvent.ofA(world)(toEventKey(logKey, n))
  )
  def create(): (String, LEvents) = {
    val logKey = idGenUtil.srcIdRandom()
    (logKey, LEvent.update(Seq(S_PubLogStart(logKey, 0L), S_PubLogEnd(logKey, 0L))))
  }
  def read(world: AssembledContext, logKey: String, pos: Long): EventLogReadResult = {
    val (start, end, getEvent) = range(world, logKey)
    val snOpt = if(pos < start) Option(getPubSnapshot.ofA(world)(logKey)) else None
    val from = snOpt.fold(pos)(_.readNextOffset)
    val vals = snOpt.map(_.value) ++ (from until end).map(getEvent(_).value)
    EventLogReadResult(vals.toSeq, end)
  }
  private def purgeRange(getEvent: Long => S_PubEvent, start: Long, end: Long): LEvents =
    (start until end).map(getEvent).flatMap(LEvent.delete)
  def purgeAll(world: AssembledContext, logKey: String): LEvents = {
    val (start, end, getEvent) = range(world, logKey)
    def rm[T<:Product](get: GetByPK[T]): LEvents = get.ofA(world).get(logKey).toSeq.flatMap(LEvent.delete)
    rm(getPubLogStart) ++ rm(getPubLogEnd) ++ rm(getPubSnapshot) ++ purgeRange(getEvent, start, end)
  }
  def write(world: AssembledContext, logKey: String, eventValue: String, snapshotOpt: Option[String]): LEvents = {
    val (wasStart, wasEnd, getEvent) = range(world, logKey)
    val willEnd = wasEnd + 1L
    val simpleRes = Seq(S_PubEvent(toEventKey(logKey, wasEnd), eventValue), S_PubLogEnd(logKey, willEnd))
      .flatMap(LEvent.update)//+
    val addRes = snapshotOpt.toSeq.flatMap{ snapshotValue =>
      val willStart = Math.max(wasStart, willEnd - 32L)
      val purgeRes = if(wasStart < willStart)
        purgeRange(getEvent, wasStart, willStart) ++ LEvent.update(S_PubLogStart(logKey, willStart))
      else Nil
      LEvent.update(S_PubSnapshot(logKey, snapshotValue, willEnd)) ++ purgeRes
    }
    simpleRes ++ addRes
  }
}
