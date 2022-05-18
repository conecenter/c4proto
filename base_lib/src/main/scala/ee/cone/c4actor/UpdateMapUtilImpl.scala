package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, N_UpdateFrom}
import ee.cone.c4actor.Types.{SrcId, UpdateKey, UpdateMap}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import okio.ByteString



@c4("ProtoApp") final class UpdateMapUtilImpl() extends UpdateMapUtil with LazyLogging {

  class UpdateMappingImpl(
    doAdd: (UpdateMap,N_UpdateFrom)=>UpdateMap,
    state: UpdateMap,
    finish: List[N_UpdateFrom]=>List[N_UpdateFrom]
  ) extends UpdateMapping {
    def add(updates: List[N_UpdateFrom]): UpdateMapping =
      new UpdateMappingImpl(doAdd, updates.foldLeft(state)(doAdd), finish)
    def result: List[N_UpdateFrom] = finish(toUpdates(state))
  }

  def startSnapshot(ignore: Set[Long]): UpdateMapping =
    start(ignore, fromEmpty=true, u=>u)
  def startRevert(ignore: Set[Long]): UpdateMapping =
    start(ignore, fromEmpty=false, _.map{u=>
      logger.debug("reverting "+toSzStr(u))
      invert(u)
    })

  private def toSzStr(u: N_UpdateFrom) =
    s"item 0x${java.lang.Long.toHexString(u.valueTypeId)} [${u.srcId}] " +
      s"${u.fromValue.size} ${u.value.size} fl:${u.flags}"

  private def toKey(up: N_UpdateFrom): UpdateKey = (up.valueTypeId,up.srcId)

  private def addLast(wasFromValue: ByteString, wasValue: ByteString, up: N_UpdateFrom): N_UpdateFrom = {
    if(up.flags != 0)
      logger.warn(s"flagged update "+toSzStr(up))
    if(wasValue != up.fromValue)
      logger.warn(s"inconsistent update from ${wasValue.size} to "+toSzStr(up))
    if(wasFromValue != up.fromValue) up.copy(fromValue = wasFromValue) else up
  }
  private def start(ignore: Set[Long], fromEmpty: Boolean, finish: List[N_UpdateFrom]=>List[N_UpdateFrom]): UpdateMapping =
    new UpdateMappingImpl((state,up) => if(ignore(up.valueTypeId)) state else{
      val key = toKey(up)
      val will = state.get(key).fold(
        if(fromEmpty) addLast(ByteString.EMPTY, ByteString.EMPTY, up) else up
      )(was=>addLast(was.fromValue, was.value, up))
      if(will.fromValue==will.value) state - key else state + (key->will)
    }, Map.empty, finish)

  def toUpdatesFrom(updates: List[N_Update], getFrom: N_Update=>ByteString): List[N_UpdateFrom] =
    toUpdates(updates.foldLeft(Map.empty:UpdateMap){ (state,u) =>
      val up = toUpdateFrom(u, getFrom(u))
      state + (toKey(up)->up)
    })

  private def toUpdates(state: UpdateMap): List[N_UpdateFrom] =
    state.values.toList.sortBy(toKey)

  private def invert(up: N_UpdateFrom) =
    up.copy(fromValue = up.value, value=up.fromValue)

  def diff(currentUpdates: List[N_UpdateFrom], targetUpdates: List[N_UpdateFrom], ignore: Set[Long]): List[N_UpdateFrom] =
    start(ignore,fromEmpty=false,u=>u)
      .add(currentUpdates.map(invert)).add(targetUpdates).result

  private def toUpdateFrom(up: N_Update, fromValue: ByteString): N_UpdateFrom = {
    val res = N_UpdateFrom(up.srcId,up.valueTypeId,fromValue,up.value,up.flags)
    logger.debug("updating "+toSzStr(res))
    res
  }

  def insert(up: N_Update): N_UpdateFrom = toUpdateFrom(up,ByteString.EMPTY)

}
