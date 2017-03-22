package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AuthProtocol.{AuthenticatedSession, PasswordChangeRequest, PasswordHashOfUser}
import ee.cone.c4proto.Protocol
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4actor.LEvent.{add, delete, update}

class TestPasswordApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
{
  override def protocols: List[Protocol] = AuthProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestPasswordAssemble ::
      new FromAlienTaskAssemble("localhost", "/react-app.html") ::
      super.assembles
}

@assemble class TestPasswordAssemble extends Assemble {
  def joinView(
    key: SrcId,
    fromAliens: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      fromAlien ← fromAliens;
      view ← Option(fromAlien.locationHash).collect{
        case "password" ⇒ TestPasswordRootView(fromAlien.fromAlienState.sessionKey)
      }
    ) yield fromAlien.branchKey → view
}

case class TestPasswordRootView(sessionKey: SrcId) extends View {
  def view: World ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local).get
    val mTags = TagsKey.of(local).get
    val world = TxKey.of(local).world
    val freshDB = By.srcId(classOf[PasswordHashOfUser]).of(world).isEmpty
    val sessions = By.srcId(classOf[AuthenticatedSession]).of(world)
    val userName: Seq[String] = sessions.getOrElse(sessionKey,Nil).map(_.userName)
    println(userName,freshDB)
    if(userName.isEmpty && !freshDB){
      List(tags.signIn())
    } else {
      List(
        tags.changePassword(obj ⇒ local ⇒ obj match {
          case v: okio.ByteString ⇒
            val reqId = v.utf8()
            val world = TxKey.of(local).world
            val requests = By.srcId(classOf[PasswordChangeRequest]).of(world)
            val updates = requests.getOrElse(reqId,Nil)
              .flatMap(req⇒update(PasswordHashOfUser("test",req.hash)))
            add(updates)(local)
        })
      ) ++ userName.map(n⇒mTags.text("hint",s"signed in as $n"))
    }
  }

}