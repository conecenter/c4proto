package ee.cone.c4gate

import java.util.concurrent.atomic.AtomicReference

import ee.cone.c4actor._
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4assemble.Types.{Each, Values}
import ammonite.sshd._
import ammonite.util.Bind
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator

trait SSHDebugApp extends AssemblesApp {
  def richRawWorldReducer: RichRawWorldReducer
  def qMessages: QMessages

  override def assembles: List[Assemble] =
    new SSHDebugAssemble(richRawWorldReducer,qMessages) :: super.assembles
}

@assemble class SSHDebugAssemble(reducer: RichRawWorldReducer, qMessages: QMessages)   {
  def join(
    key: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(SSHDebugTx()(reducer,qMessages)))
}

case class SSHDebugTx(srcId: SrcId="SSHDebug")(
  reducer: RichRawWorldReducer,
  qMessages: QMessages
) extends TxTransform {
  def init(): RichContext⇒Unit = {
    val ref = new AtomicReference[Option[RichContext]](None)
    def ctx(): RichContext = ref.get.get
    def tx(f: Context⇒Object): List[_] = {
      val context = ref.get.get
      f(new Context(context.injected,context.assembled,Map.empty)) match {
        case local: Context ⇒
          qMessages.send(local)
          Nil
        case res: List[_] ⇒ res
      }
    }
    val server = new SshdRepl(
      SshServerConfig(
        address = "localhost", // or "0.0.0.0" for public-facing shells
        port = 22222,
        publicKeyAuthenticator = Option(AcceptAllPublickeyAuthenticator.INSTANCE)
      ),
      replArgs = List(Bind[(Context⇒Object)⇒Object]("tx",tx))
    )
    server.start()
    v⇒ref.set(Option(v))
  }
  def transform(local: Context): Context = {
    val nLocal = if(SSHDebugKey.of(local).nonEmpty) local
      else SSHDebugKey.set(Option(init()))(local)
    SSHDebugKey.of(nLocal).get(reducer.reduce(Option(nLocal),Nil))
    nLocal
  }
}


/*
byPK(classOf[T])
add(lEvents)
commit()
rollback()
 */
/*
import ee.cone.c4actor._
import ee.cone.c4gate._
tx(ByPK(classOf[AlienProtocol.FromAlienState]).of(_).values.toList.sortBy(_.sessionKey))
tx(TxAdd(LEvent.delete(AlienProtocol.FromAlienState("61297c47-c5de-4fd9-add9-1967615a44a8", "https://skh.dev.cone.ee/react-app.html", "61297c47-c5de-4fd9-add9-1967615a44a8", None))))
 */

case object SSHDebugKey extends TransientLens[Option[RichContext⇒Unit]](None)
