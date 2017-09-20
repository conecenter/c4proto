package ee.cone.c4actor
/*
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.SessionDataProtocol.RawSessionData
import ee.cone.c4actor.SessionDataOperations._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4proto.{Id, Protocol, protocol}


@protocol object SessionDataProtocol extends Protocol { //...
  case class RawSessionData(
    srcId: String,
    @Id(0x0058) userName: String,
    @Id(0x005A) sessionKey: String,
    domainSrcId: String,
    fieldId: Long,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString,
    until: Long
  )
}

case class SessionData(srcId: String, orig: RawSessionData, value: Product)


@assemble class SessionDataAssemble[Session <: Product](
  classOfSession: Class[Session],
  registry: QAdapterRegistry
) extends Assemble { //...

  def joinRaw(
    srcId: SrcId,
    rawDataValues: Values[RawSessionData]
  ): Values[(SrcId, SessionData)] = for {
    r ← rawDataValues
    adapter ← registry.byId.get(r.valueTypeId)
  } yield WithPK(SessionData(r.srcId, r, adapter.decode(r.value.toByteArray)))

  type SessionKey = SrcId

  def joinBySessionKey(
    srcId: SrcId,
    sessionDataValues: Values[SessionData]
  ): Values[(SessionKey, SessionData)] = for {
    sd ← sessionDataValues if sd.orig.sessionKey.nonEmpty
  } yield sd.orig.sessionKey → sd

  def joinLife(
    key: SrcId,
    sessions: Values[Session],
    @by[SessionKey] sessionDataValues: Values[SessionData]
  ): Values[(Alive, SessionData)] = for {
    session ← sessions
    sessionData ← sessionDataValues
  } yield WithPK(sessionData)
}

//...life for user's, mortal

trait SessionDataAccess[Model,Value] extends FieldAccess[Value] {
  def ofField[V](f: Model⇒V): SessionDataAccess[Model,V]
  def ofField[V](of: Model⇒V, set: V⇒Model⇒Model, postfix: String): SessionDataAccess[Model,V]
  def model[P <: Product](cl: Class[P]): SessionDataAccess[P,Value]
  def srcId(srcId: SrcId): SessionDataAccess[Model,Value]
  def context(local: Context): SessionDataAccess[Model,Value]
}

case class SessionDataAccessImpl[Model,Value] extends SessionDataAccess[Model,Value] {


  def model[P <: Product](cl: Class[P]): SessionDataAccess[P,Value] =
    SessionDataAccessImpl(cl.getName,keyOpt)

  def ofField[V](f: Model⇒V): FieldMetaBuilder[Model,Value] = throw new Exception("macro not expanded")
  def ofField(of: Model=>Any, set: Nothing=>Model=>Model, postfix: String): FieldMetaBuilder[Model,Value] =
    copy(name + postfix)
  def set(value: Value): List[Injectable] =
    keyOpt.get.set(Map(name→value))



  def competingKey(sessionData: SessionData): SrcId = {
    ???
    //usdft
  }
}*/