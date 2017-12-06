package ee.cone.c4gate


import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.TcpProtocol._

trait TcpServerApp extends `The TcpAssemble` with `The TcpProtocol`
  with `The TcpServerInject` with `The TcpServerExecutable`
  with `The TcpServerImpl` with `The TcpServerConfigImpl` with `The TcpHandlerImpl`
  with `The TcpDisconnectMortal` with `The TcpWriteMortal`


@c4component @listed case class TcpDisconnectMortal() extends Mortal(classOf[TcpDisconnect])
@c4component @listed case class TcpWriteMortal() extends Mortal(classOf[TcpWrite])

@c4component case class TcpServerConfigImpl(config: Config) extends TcpServerConfig {
  def port: Int = config.get("C4TCP_PORT").toInt
  def timeout: Long = Long.MaxValue
}

@c4component case class TcpHandlerImpl(
  qMessages: QMessages, worldProvider: WorldProvider,
  tcpConnections: ByPK[TcpConnection] @c4key
) extends TcpHandler {
  private def changeWorld(transform: Context ⇒ Context): Unit =
    Option(worldProvider.createTx()).map(transform).foreach(qMessages.send)
  override def beforeServerStart(): Unit = changeWorld{ local ⇒
    val connections = tcpConnections.of(local).values.toList
    TxAdd(connections.flatMap(LEvent.delete))(local)
  }
  override def afterConnect(key: String, sender: SenderToAgent): Unit =
    changeWorld(TxAdd(LEvent.update(TcpConnection(key))))
  override def afterDisconnect(key: String): Unit =
    changeWorld(TxAdd(LEvent.delete(TcpConnection(key))))
}

case class TcpConnectionTxTransform(
    connectionKey: SrcId,
    tcpDisconnects: Values[TcpDisconnect],
    writes: Values[TcpWrite]
) extends TxTransform {
  def transform(local: Context): Context = {
    def sender = GetSenderKey.of(local)(connectionKey)
    for(d ← tcpDisconnects; s ← sender) s.close()
    for(message ← writes; s ← sender) s.add(message.body.toByteArray)
    TxAdd(writes.flatMap(LEvent.delete))(local)
  }
}

@assemble class TcpAssemble {
  type ConnectionKey = SrcId

  def joinTcpWrite(key: SrcId, writes: Values[TcpWrite]): Values[(ConnectionKey, TcpWrite)] =
    writes.map(write⇒write.connectionKey→write)

  def joinTxTransform(
      key: SrcId,
      tcpConnections: Values[TcpConnection],
      tcpDisconnects: Values[TcpDisconnect],
      @by[ConnectionKey] writes: Values[TcpWrite]
  ): Values[(SrcId,TxTransform)] =
    for(c ← tcpConnections)
      yield WithPK(TcpConnectionTxTransform(c.connectionKey, tcpDisconnects, writes.sortBy(_.priority)))

  def lifeOfConnectionToDisconnects(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect]
  ): Values[(Alive,TcpDisconnect)] =
    for(d ← tcpDisconnects if tcpConnections.nonEmpty) yield WithPK(d)

  def lifeOfConnectionToTcpWrites(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    @by[ConnectionKey] writes: Values[TcpWrite]
  ): Values[(Alive,TcpWrite)] =
    for(d ← writes if tcpConnections.nonEmpty) yield WithPK(d)
}
