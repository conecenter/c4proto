
package ee.cone.c4gate

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4proto.{Id, protocol}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Context, LEvent, LTxAdd, TxTransform}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.c4multi
import ee.cone.c4gate.AddrForKeyProtocol.S_AddrForKeyCode
import ee.cone.c4gate.{ByPathHttpPublication, Publisher}
import ee.cone.c4proto.{Id, ToByteString, protocol}

@protocol("RoomsConfProtocolApp") object AddrForKeyProtocol {
  @Id(0x006E) case class S_AddrForKeyCode(@Id(0x002A) srcId: String, @Id(0x006D) code: Long)
  //@Id(0x006D) case class S_RoomsConf
}

@c4assemble("RoomsConfProtocolApp") class AddrForKeyAssembleBase(theAddrForKeyTxFactory: AddrForKeyTxFactory){
  type ByPublisher = SrcId
  def optionalFeature(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId, AddrForKey)] = Nil
  def map(key: SrcId, rc: Each[AddrForKey]): Values[(ByPublisher,AddrForKey)] = Seq("AddrForKey"->rc)
  def join(
    key: SrcId, @by[ByPublisher] targets: Values[AddrForKey],  codes: Values[S_AddrForKeyCode],
  ): Values[(SrcId, TxTransform)] = {
    val targetList = targets.sortBy(_.srcId).toList
    val willCode = S_AddrForKeyCode(key, targetList.hashCode)
    //println(s"PUA ${codes.contains(willCode)}")
    if(codes.contains(willCode)) Nil else Seq(key->theAddrForKeyTxFactory.create(willCode, targetList))
  }
}

@c4multi("RoomsConfProtocolApp") final case class AddrForKeyTx(code: S_AddrForKeyCode, streams: List[AddrForKey])(
  publisher: Publisher, txAdd: LTxAdd,
) extends TxTransform {
  def transform(local: Context): Context = {
    val publications = streams.map(s => ByPathHttpPublication(s"/addr4key/${s.srcId}", Nil, ToByteString(s.addr)))
    //println(s"PUUU: $publications")
    val lEvents: Seq[LEvent[Product]] = LEvent.update(code) ++ publisher.publish(code.srcId, publications)(local)
    txAdd.add(lEvents)(local)
  }
}
