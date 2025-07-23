package ee.cone.c4actor

import com.squareup.wire.{ProtoReader, ProtoWriter}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, N_UpdateFrom}
import ee.cone.c4actor.Types.{UpdateKey, UpdateMap}
import ee.cone.c4di.c4
import ee.cone.c4proto.{FieldEncoding, ProtoAdapter}
import okio.ByteString

import scala.annotation.tailrec



final class RawObjectListProtoAdapter extends ProtoAdapter[List[(Int,Any)]](
  FieldEncoding.LENGTH_DELIMITED, classOf[List[_]]
){
  type Res = List[(Int,Any)]
  @tailrec def decodeMore(reader: ProtoReader, res: Res): Res = reader.nextTag() match {
    case -1 => res.reverse
    case tag =>
      val r = reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      decodeMore(reader, (tag,r)::res)
  }
  def decode(reader: ProtoReader): Res = {
    val token = reader.beginMessage()
    val res = decodeMore(reader, Nil)
    reader.endMessage(token)
    res
  }
  def encode(protoWriter: ProtoWriter, e: Res): Unit = throw new Exception("not implemented")
  def encodedSize(e: Res): Int = throw new Exception("not implemented")
  def redact(e: Res): Res = e
}

@c4("ProtoApp") final class UpdateMapUtilImpl(
  val adapter: RawObjectListProtoAdapter = new RawObjectListProtoAdapter
) extends UpdateMapUtil with LazyLogging {
  //private val strict = strictList.map(s => qAdapterRegistry.byName(s.cl.getName).id).toSet

  private class UpdateMappingImpl(
    doAdd: (N_UpdateFrom=>Boolean)=>(UpdateMap,N_UpdateFrom)=>UpdateMap,
    state: UpdateMap,
    finish: List[N_UpdateFrom]=>List[N_UpdateFrom]
  ) extends UpdateMapping {
    def add(updates: List[N_UpdateFrom], onError: N_UpdateFrom=>Boolean): UpdateMapping =
      new UpdateMappingImpl(doAdd, updates.foldLeft(state)(doAdd(onError)), finish)
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

  private def eqEncoded(a: ByteString, b: ByteString): Boolean =
    if(a == b) true
    else if(a.size != b.size) false
    else {
      val al = adapter.decode(a).sortBy(_._1)
      val bl = adapter.decode(b).sortBy(_._1)
      //logger.warn(s"al $al")
      al == bl
    }

  private def addLast(
    wasFromValue: ByteString, wasValue: ByteString, up: N_UpdateFrom, onError: N_UpdateFrom=>Boolean
  ): N_UpdateFrom = {
    if(up.flags != 0)
      logger.warn(s"flagged update "+toSzStr(up))
    if(!eqEncoded(wasValue,up.fromValue) && onError(up)) {
      logger.warn(s"inconsistent update from ${wasValue.size} to "+toSzStr(up))
      logger.debug(s"${adapter.decode(wasValue)} vs ${adapter.decode(up.fromValue)}")
    }
    if(wasFromValue != up.fromValue) up.copy(fromValue = wasFromValue) else up
  }
  private def start(ignore: Set[Long], fromEmpty: Boolean, finish: List[N_UpdateFrom]=>List[N_UpdateFrom]): UpdateMapping =
    new UpdateMappingImpl(onError => (state,up) => if(ignore(up.valueTypeId)) state else{
      val key = toKey(up)
      val will = state.get(key).fold(
        if(fromEmpty) addLast(ByteString.EMPTY, ByteString.EMPTY, up, onError) else up
      )(was=>addLast(was.fromValue, was.value, up, onError))
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

  private def diffError(up: N_UpdateFrom): Boolean = throw new Exception("diff error")
  def diff(currentUpdates: List[N_UpdateFrom], targetUpdates: List[N_UpdateFrom], ignore: Set[Long]): List[N_UpdateFrom] =
    start(ignore,fromEmpty=false,u=>u)
      .add(currentUpdates.map(invert), diffError).add(targetUpdates, diffError).result

  private def toUpdateFrom(up: N_Update, fromValue: ByteString): N_UpdateFrom = {
    val res = N_UpdateFrom(up.srcId,up.valueTypeId,fromValue,up.value,up.flags)
    logger.debug("updating "+toSzStr(res))
    res
  }

  def insert(up: N_Update): N_UpdateFrom = toUpdateFrom(up,ByteString.EMPTY)

}
