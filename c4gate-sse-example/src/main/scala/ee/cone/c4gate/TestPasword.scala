package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AuthProtocol.{PasswordChangeRequest, PasswordHashOfUser}
import ee.cone.c4proto.Protocol
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4actor.LEvent.{add,update}
import ee.cone.c4gate.AlienProtocol.FromAlienState

class TestPasswordApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with UMLClientsApp
  with ManagementApp
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
        case "password" ⇒ TestPasswordRootView(fromAlien.branchKey,fromAlien.fromAlienState)
        case "anti-dos" ⇒ TestAntiDosRootView(fromAlien.branchKey)
      }
    ) yield WithSrcId(view)
}

case class TestAntiDosRootView(branchKey: SrcId) extends View {
  def view: World ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local) { () ⇒
    val mTags = TagsKey.of(local).get
    import mTags._
    List(
      text("text","press it many times and look at 429 in browser console"),
      divButton("add"){(local:World) ⇒
        Thread.sleep(1000)
        local
      }(List(text("text","wait 1s")))
    )
  }
}

case class TestPasswordRootView(branchKey: SrcId, fromAlienState: FromAlienState) extends View {
  def view: World ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local).get
    val mTags = TagsKey.of(local).get
    val world = TxKey.of(local).world
    val freshDB = By.srcId(classOf[PasswordHashOfUser]).of(world).isEmpty
    val userName = fromAlienState.userName
    println(userName,freshDB)
    if(userName.isEmpty && !freshDB){
      List(tags.signIn(newSessionKey ⇒ local ⇒ {
        if(newSessionKey.isEmpty) throw new Exception with BranchError {
          def message: String = "Bad username or password"
        }
        local
      }))
    } else {
      List(
        tags.changePassword(message ⇒ local ⇒ {
          val reqId = tags.messageStrBody(message)
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