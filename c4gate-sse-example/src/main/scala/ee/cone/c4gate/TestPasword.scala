package ee.cone.c4gate

import java.time.Instant

import ee.cone.c4actor.Types.{ByPK, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AuthProtocol.{PasswordChangeRequest, PasswordHashOfUser}
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4actor.LEvent.update
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4vdom.Tags

class TestPasswordApp extends ServerApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaProducerApp with KafkaConsumerApp with FileRawSnapshotApp
  with `The ParallelObserverProvider` with TreeIndexValueMergerFactoryApp
  with `The NoAssembleProfiler`
  with UIApp with ManagementApp
  with `The PublicViewAssemble` with `The ByLocationHashView`
  with `The TestTagsImpl`
  with `The ReactAppAssemble`
  ////
  with `The AuthProtocol`
  with `The TestPasswordRootView`
  with `The TestAntiDosRootView`
  with `The TestFailuresRootView`

case object TestFailUntilKey extends TransientLens[(Instant,Instant)]((Instant.MIN,Instant.MIN))

@c4component @listed case class TestFailuresRootView(locationHash: String = "failures")(
  tags: TestTags,
  mTags: Tags, untilPolicy: UntilPolicy,
  sessionFailures: ByPK[SessionFailures] @c4key
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    import mTags._
    val now = Instant.now
    val (from,to) = TestFailUntilKey.of(local)
    if(from.isBefore(now) && now.isBefore(to)) throw new Exception

    val failures = sessionFailures.of(local)
      .get(CurrentSessionKey.of(local)).toList.flatMap(_.failures)
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

@c4component @listed case class TestAntiDosRootView(locationHash: String = "anti-dos")(
  mTags: Tags, untilPolicy: UntilPolicy
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
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

@c4component @listed case class TestPasswordRootView(locationHash: String = "anti-dos")(
  tags: TestTags,
  mTags: Tags, untilPolicy: UntilPolicy,
  hashes: ByPK[PasswordHashOfUser] @c4key,
  passwordChangeRequests: ByPK[PasswordChangeRequest] @c4key,
  fromAlienStates: ByPK[FromAlienState] @c4key
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    val freshDB = hashes.of(local).isEmpty
    val sessionKey = CurrentSessionKey.of(local)
    val userName = fromAlienStates.of(local)(sessionKey).userName
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
          val requests = passwordChangeRequests.of(local)
          val updates = requests.get(reqId).toList
            .flatMap(req⇒update(PasswordHashOfUser("test",req.hash)))
          TxAdd(updates)(local)
        })
      ) ++ userName.map(n⇒mTags.text("hint",s"signed in as $n"))
    }
  }
}

