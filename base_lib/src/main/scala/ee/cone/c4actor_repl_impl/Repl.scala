package ee.cone.c4actor_repl_impl

import java.util.concurrent.atomic.AtomicReference
import ee.cone.c4actor._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}
import ammonite.sshd._
import ammonite.util.Bind
import ee.cone.c4di.c4multi
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator

@c4assemble("SSHDebugApp") class SSHDebugAssembleBase(factory: SSHDebugTxFactory)   {
  def join(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(factory.create()))
}

@c4multi("SSHDebugApp") final case class SSHDebugTx(srcId: SrcId="SSHDebug")(
  qMessages: QMessages, replace: Replace,
) extends TxTransform {
  def init(ec: OuterExecutionContext): ReadModel=>Unit = {
    val ref = new AtomicReference[ReadModel](replace.emptyReadModel)
    def tx(f: Context=>Object): List[_] = {
      val assembled = ref.get
      f(new Context(assembled, ec, Map.empty)) match {
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
    v => ref.set(v)
  }
  def transform(local: Context): Context = {
    val nLocal = if(SSHDebugKey.of(local).nonEmpty) local
      else SSHDebugKey.set(Option(init(local.executionContext)))(local)
    SSHDebugKey.of(nLocal).get(nLocal.assembled)
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
tx(ByPK(classOf[AlienProtocol.U_FromAlienState]).of(_).values.toList.sortBy(_.sessionKey))
tx(txAdd.add(LEvent.delete(AlienProtocol.U_FromAlienState("61297c47-c5de-4fd9-add9-1967615a44a8", "https://skh.dev.cone.ee/react-app.html", "61297c47-c5de-4fd9-add9-1967615a44a8", None))))
 */

case object SSHDebugKey extends TransientLens[Option[ReadModel=>Unit]](None)
