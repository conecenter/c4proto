package ee.cone.c4gate


import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.TcpProtocol._
import ee.cone.c4proto.Protocol

trait TcpServerApp extends ToStartApp with AssemblesApp with ToInjectApp with ProtocolsApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  def mortal: MortalFactory

  private lazy val tcpPort = config.get("C4TCP_PORT").toInt
  private lazy val tcpServer = new TcpServerImpl(tcpPort, new TcpHandlerImpl(qMessages, worldProvider), Long.MaxValue, NoStreamCompressorFactory)
  override def toStart: List[Executable] = tcpServer :: super.toStart
  override def assembles: List[Assemble] =
    mortal(classOf[S_TcpDisconnect]) :: mortal(classOf[S_TcpWrite]) ::
    new TcpAssemble :: super.assembles
  override def toInject: List[ToInject] = tcpServer :: super.toInject
  override def protocols: List[Protocol] = TcpProtocol :: super.protocols
}

class TcpHandlerImpl(qMessages: QMessages, worldProvider: WorldProvider) extends TcpHandler {
  private def changeWorld(transform: Context ⇒ Context): Unit =
    Option(worldProvider.createTx()).map(transform).foreach(qMessages.send)
  override def beforeServerStart(): Unit = changeWorld{ local ⇒
    val connections = ByPK(classOf[S_TcpConnection]).of(local).values.toList
    TxAdd(connections.flatMap(LEvent.delete))(local)
  }
  override def afterConnect(key: String, sender: SenderToAgent): Unit =
    changeWorld(TxAdd(LEvent.update(S_TcpConnection(key))))
  override def afterDisconnect(key: String): Unit =
    changeWorld(TxAdd(LEvent.delete(S_TcpConnection(key))))
}

case class TcpConnectionTxTransform(
    connectionKey: SrcId,
    tcpDisconnects: Values[S_TcpDisconnect],
    writes: Values[S_TcpWrite]
) extends TxTransform {
  def transform(local: Context): Context = {
    def sender = GetSenderKey.of(local)(connectionKey)
    for(d ← tcpDisconnects; s ← sender) s.close()
    for(message ← writes; s ← sender) s.add(message.body.toByteArray)
    TxAdd(writes.flatMap(LEvent.delete))(local)
  }
}

@assemble class TcpAssembleBase   {
  type ConnectionKey = SrcId

  def joinTcpWrite(key: SrcId, write: Each[S_TcpWrite]): Values[(ConnectionKey, S_TcpWrite)] =
    List(write.connectionKey→write)

  def joinTxTransform(
      key: SrcId,
      c: Each[S_TcpConnection],
      tcpDisconnects: Values[S_TcpDisconnect],
      @by[ConnectionKey] writes: Values[S_TcpWrite]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(TcpConnectionTxTransform(c.connectionKey, tcpDisconnects, writes.sortBy(_.priority))))

  def lifeOfConnectionToDisconnects(
    key: SrcId,
    tcpConnections: Values[S_TcpConnection],
    d: Each[S_TcpDisconnect]
  ): Values[(Alive,S_TcpDisconnect)] =
    if(tcpConnections.nonEmpty) List(WithPK(d)) else Nil

  def lifeOfConnectionToTcpWrites(
    key: SrcId,
    tcpConnections: Values[S_TcpConnection],
    @by[ConnectionKey] d: Each[S_TcpWrite]
  ): Values[(Alive,S_TcpWrite)] =
    if(tcpConnections.nonEmpty) List(WithPK(d)) else Nil
}
