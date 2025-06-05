package ee.cone.c4actor_repl_impl

import java.util.concurrent.atomic.AtomicReference
import ee.cone.c4actor._
import ee.cone.c4actor.Types.{SrcId, TxEvents}
import ee.cone.c4assemble._
import ammonite.sshd._
import ammonite.util.Bind
import ee.cone.c4di.c4
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator

@c4("SSHDebugApp") final case class SSHDebugTx(srcId: SrcId="SSHDebug")(
  qMessages: QMessages, replace: Replace,
) extends SingleTxTr with Executable with Early {
  private val ref = new AtomicReference[ReadModel](replace.emptyReadModel)
  def run(): Unit = {
    def tx(f: Context=>Object): List[_] = {
      val assembled = ref.get
      f(new Context(assembled, Map.empty)) match {
        case local: Context =>
          val nLocal = qMessages.send(local)
          Nil
        case res: List[_] => res
      }
    }
    val server = new SshdRepl(
      SshServerConfig(
        address = "localhost", // or "0.0.0.0" for public-facing shells
        port = 22222,
        publicKeyAuthenticator = Option(AcceptAllPublickeyAuthenticator.INSTANCE)
      ),
      replArgs = List(Bind[(Context=>Object)=>Object]("tx",tx))
    )
    server.start()
  }
  def transform(local: Context): TxEvents = {
    ref.set(local.assembled)
    Nil
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
tx(ByPK(classOf[AlienProtocol.U_FromAlienState]).of(_).values.toList.sortBy(_.sessionKey))
tx(txAdd.add(LEvent.delete(AlienProtocol.U_FromAlienState("61297c47-c5de-4fd9-add9-1967615a44a8", "https://skh.dev.cone.ee/react-app.html", "61297c47-c5de-4fd9-add9-1967615a44a8", None))))
 */
