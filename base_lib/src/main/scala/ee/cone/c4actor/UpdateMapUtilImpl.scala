package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, N_UpdateFrom}
import ee.cone.c4actor.Types.{SrcId, UpdateKey, UpdateMap}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import okio.ByteString

@c4("ProtoApp") final class UpdateMapUtilImpl() extends UpdateMapUtil with LazyLogging {

  private def toSzStr(u: N_UpdateFrom) =
    s"item 0x${java.lang.Long.toHexString(u.valueTypeId)} [${u.srcId}] " +
      s"${u.lessValues.map(_.size).sum}/${u.lessValues.size} " +
      s"${u.moreValues.map(_.size).sum}/${u.moreValues.size} " +
      s"${u.value.size} fl:${u.flags}"

  private def toKey(up: N_UpdateFrom): UpdateKey = (up.valueTypeId,up.srcId)

  def reduce(state: UpdateMap, updates: List[N_UpdateFrom], ignore: Set[Long]): UpdateMap =
    updates.foldLeft(state){ (st,up) =>
      if(ignore(up.valueTypeId)) st else addMerge(st, up)
    }
  private def addMerge(state: UpdateMap, up: N_UpdateFrom): UpdateMap = {
    val key = toKey(up)
    val will = state.get(key).fold(up){ was =>
      val longLessValues = was.lessValues ::: up.lessValues
      val pMoreValues = if(was.value.size==0) Nil else was.value :: Nil
      val longMoreValues = was.moreValues ::: pMoreValues ::: up.moreValues
      val same = longLessValues == longMoreValues
      val lessValues = if(same) Nil else longLessValues.diff(longMoreValues)
      val moreValues = if(same) Nil else longMoreValues.diff(longLessValues)
      up.copy(lessValues=lessValues,moreValues=moreValues)
    }
    if(will.value.size==0 && will.lessValues.isEmpty && will.moreValues.isEmpty)
      state - key else state + (key->will)
  }

  def toUpdatesFrom(updates: List[N_Update], getFrom: N_Update=>List[ByteString]): List[N_UpdateFrom] =
    toUpdates(updates.foldLeft(Map.empty:UpdateMap){ (state,u) =>
      val up = toUpdateFrom(u, getFrom(u))
      state + (toKey(up)->up)
    })

  def toSingleUpdates(state: UpdateMap): List[N_UpdateFrom] = {
    val res = toUpdates(state)
    for(up <- res)
      if(up.lessValues.nonEmpty || up.moreValues.nonEmpty || up.value.size==0)
        logger.warn("non-single "+toSzStr(up))
    res
  }

  def toUpdates(state: UpdateMap): List[N_UpdateFrom] =
    state.values.toList.sortBy(toKey)

  def revert(state: UpdateMap): List[N_UpdateFrom] = toUpdates(state).map{ up =>
    val (value,moreValues) = if(up.lessValues.isEmpty) (ByteString.EMPTY,Nil)
      else (up.lessValues.head,up.lessValues.tail)
    val lessValues = up.moreValues ::: toLessValues(up.value)
    //
    if(moreValues.exists(_!=value) || up.flags!=0L)
      logger.error("reverting bad "+toSzStr(up))
    else if(moreValues.nonEmpty)
      logger.warn("reverting inconsistent-more "+toSzStr(up))
    else if(lessValues.size > 1)
      logger.warn("reverting inconsistent-less "+toSzStr(up))
    else logger.debug("reverting "+toSzStr(up))
    //
    up.copy(lessValues=lessValues, moreValues=moreValues, value=value)
  }

  private def toLessValues(b: ByteString) = if(b.size==0) Nil else b :: Nil

  private def toUpdateMap(updates: List[N_UpdateFrom], ignore: Set[Long]): Map[(Long,SrcId),ByteString] =
    CheckedMap(for(up<-updates if !ignore(up.valueTypeId)) yield {
      assert(up.flags==0L)
      toKey(up)->up.value
    })

  def diff(currentUpdates: List[N_UpdateFrom], targetUpdates: List[N_UpdateFrom], ignore: Set[Long]): List[N_UpdateFrom] = {
    val currentMap = toUpdateMap(currentUpdates, ignore)
    val targetMap = toUpdateMap(targetUpdates, ignore)
    (currentMap.keySet ++ targetMap.keySet).toList.sorted.flatMap{ k =>
      val currentB = currentMap.getOrElse(k,ByteString.EMPTY)
      val targetB = targetMap.getOrElse(k,ByteString.EMPTY)
      if(currentB==targetB) Nil else {
        val (valueTypeId,srcId) = k
        N_UpdateFrom(srcId,valueTypeId,toLessValues(currentB),Nil,targetB,0L) :: Nil
      }
    }
  }

  def toUpdateFrom(up: N_Update, fromValues: List[ByteString]): N_UpdateFrom = {
    val res = N_UpdateFrom(up.srcId,up.valueTypeId,fromValues,Nil,up.value,up.flags)
    logger.debug("updating "+toSzStr(res))
    res
  }

  def insert(up: N_Update): N_UpdateFrom = toUpdateFrom(up,Nil)
}
