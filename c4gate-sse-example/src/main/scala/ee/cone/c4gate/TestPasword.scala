package ee.cone.c4gate

import java.time.Instant

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AuthProtocol.{PasswordChangeRequest, PasswordHashOfUser}
import ee.cone.c4proto.Protocol
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4actor.LEvent.update
import ee.cone.c4gate.AlienProtocol.FromAlienState

class TestPasswordApp extends ServerApp
  with `The EnvConfigImpl` with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with UIApp
  with `The TestTagsImpl`
  with `The NoAssembleProfiler`
  with ManagementApp
  with FileRawSnapshotApp
  with `The AuthProtocol`
  with `The TestPasswordAssemble`
  with `The ReactAppAssemble`

@c4component @listed @assemble case class TestPasswordAssemble() extends Assemble {
  def joinView(
    key: SrcId,
    fromAliens: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      fromAlien ← fromAliens;
      view ← Option(fromAlien.locationHash).collect{
        case "password" ⇒ ??? //TestPasswordRootView(fromAlien.branchKey,fromAlien.fromAlienState)
        case "anti-dos" ⇒ ??? //TestAntiDosRootView(fromAlien.branchKey)
        case "failures" ⇒ ??? //TestFailuresRootView(fromAlien.branchKey,fromAlien.fromAlienState)
      }
    ) yield WithPK(view)
}

case object TestFailUntilKey extends TransientLens[(Instant,Instant)]((Instant.MIN,Instant.MIN))
/*
case class TestFailuresRootView(branchKey: SrcId, fromAlienState: FromAlienState) extends View {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local) { () ⇒
    val mTags = TagsKey.of(local)
    import mTags._
    val now = Instant.now
    val (from,to) = TestFailUntilKey.of(local)
    if(from.isBefore(now) && now.isBefore(to)) throw new Exception

    val failures = ByPK(classOf[SessionFailures]).of(local)
      .get(fromAlienState.sessionKey).toList.flatMap(_.failures)
      .map(err⇒text(err.srcId,s"[${err.text}]"))
    failures ::: List(
      divButton("bae"){(local:Context) ⇒
        throw new Exception with BranchError {
          override def message: String = "Action Error"
        }
      }(List(text("text","[Custom Action Error]"))),
      divButton("iae"){(local:Context) ⇒
        throw new Exception
      }(List(text("text","[Internal Action Error]"))),
      divButton("ive"){
        TestFailUntilKey.set((now.plusSeconds(-1),now.plusSeconds(3)))
      }(List(text("text","[Instant View Error]"))),
      divButton("dve"){
        TestFailUntilKey.set((now.plusSeconds(1),now.plusSeconds(3)))
      }(List(text("text","[Deferred View Error]")))
    )
  }
}

case class TestAntiDosRootView(branchKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local) { () ⇒
    val mTags = TagsKey.of(local)
    import mTags._
    List(
      text("text","press it many times and look at 429 in browser console: "),
      divButton("add"){(local:Context) ⇒
        Thread.sleep(1000)
        local
      }(List(text("text","[wait 1s]")))
    )
  }
}

case class TestPasswordRootView(branchKey: SrcId, fromAlienState: FromAlienState) extends View {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local)
    val mTags = TagsKey.of(local)
    val freshDB = ByPK(classOf[PasswordHashOfUser]).of(local).isEmpty
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
          val requests = ByPK(classOf[PasswordChangeRequest]).of(local)
          val updates = requests.get(reqId).toList
            .flatMap(req⇒update(PasswordHashOfUser("test",req.hash)))
          TxAdd(updates)(local)
        })
      ) ++ userName.map(n⇒mTags.text("hint",s"signed in as $n"))
    }
  }

}
*/
