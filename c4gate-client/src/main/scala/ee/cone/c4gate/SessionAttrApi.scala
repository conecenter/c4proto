package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.Id

object SessionAttr {
  def apply[B](id: Id, cl: Class[B], values: AbstractMetaAttr*): SessionAttr[B] =
    SessionAttr(
      className = cl.getName,
      id = id.id,
      pk = "",
      metaList = NameMetaAttr(s"${id.id}") :: values.toList
    )
}

case class WithPKMetaAttr(pk: String) extends AbstractMetaAttr

case class SessionAttr[+By](
  className: String, id: Long, pk: SrcId, metaList: List[AbstractMetaAttr]
){
  def withPK(nPK: SrcId): SessionAttr[By] = copy(pk=nPK, metaList= WithPKMetaAttr(nPK) :: metaList)
}

trait SessionAttrAccessFactory {
  def to[P<:Product](attr: SessionAttr[P]): Context⇒Option[Access[P]]
}

case object CurrentSessionKey extends TransientLens[SrcId]("")
