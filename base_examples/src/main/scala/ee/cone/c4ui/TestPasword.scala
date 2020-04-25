package ee.cone.c4ui

import java.time.Instant

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchError
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.AuthProtocol.{C_PasswordHashOfUser, S_PasswordChangeRequest}
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.CurrentSessionKey
import ee.cone.c4ui._
import ee.cone.c4vdom.Tags
import ee.cone.c4vdom.Types.ViewRes

/*
@c4assemble("TestPasswordApp") class TestPasswordAssembleBase   {
  def joinView(
    key: SrcId,
    fromAlien: Each[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      view <- List(fromAlien.locationHash).collect{
        case "anti-dos" => ??? //TestAntiDosRootView(fromAlien.branchKey)
        case "failures" => ??? //TestFailuresRootView(fromAlien.branchKey,fromAlien.fromAlienState)
      }
    ) yield WithPK(view)
}*/

case object TestFailUntilKey extends TransientLens[(Instant,Instant)]((Instant.MIN,Instant.MIN))
/*
case class TestFailuresRootView(branchKey: SrcId, fromAlienState: FromAlienState) extends View {
  def view: Context => ViewRes = local => UntilPolicyKey.of(local) { () =>
    val mTags = TagsKey.of(local)
    import mTags._
    val now = Instant.now
    val (from,to) = TestFailUntilKey.of(local)
    if(from.isBefore(now) && now.isBefore(to)) throw new Exception

    val failures =  /*getSessionFailures: GetByPK[SessionFailures],*/getSessionFailures.ofA(local)
      .get(fromAlienState.sessionKey).toList.flatMap(_.failures)
      .map(err=>text(err.srcId,s"[${err.text}]"))
    failures ::: List(
      divButton("bae"){(local:Context) =>
        throw new Exception with BranchError {
          override def message: String = "Action Error"
        }
      }(List(text("text","[Custom Action Error]"))),
      divButton("iae"){(local:Context) =>
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
  def view: Context => ViewRes = local => UntilPolicyKey.of(local) { () =>
    val mTags = TagsKey.of(local)
    import mTags._
    List(
      text("text","press it many times and look at 429 in browser console: "),
      divButton("add"){(local:Context) =>
        Thread.sleep(1000)
        local
      }(List(text("text","[wait 1s]")))
    )
  }
}
*/

@c4("TestPasswordApp") final case class TestPasswordRootView(locationHash: String = "pass")(
  tags: TestTags[Context],
  mTags: Tags,
  untilPolicy: UntilPolicy,
  getC_PasswordHashOfUser: GetByPK[C_PasswordHashOfUser],
  getU_FromAlienState: GetByPK[U_FromAlienState],
  getS_PasswordChangeRequest: GetByPK[S_PasswordChangeRequest],
  getU_AuthenticatedSession: GetByPK[U_AuthenticatedSession],
  txAdd: LTxAdd,
) extends ByLocationHashView {
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val freshDB = getC_PasswordHashOfUser.ofA(local).isEmpty
    val sessionKey = CurrentSessionKey.of(local)
    val fromAlienState = getU_FromAlienState.ofA(local)(sessionKey)
    val userName = fromAlienState.userName
    println(userName,freshDB)
    if(userName.isEmpty && !freshDB){
      List(tags.signIn(newSessionKey => local => {
        if(newSessionKey.isEmpty) throw new Exception with BranchError {
          def message: String = "Bad username or password"
        }
        local
      }))
    } else {
      List(
        tags.changePassword(message => local => {
          val reqId = tags.messageStrBody(message)
          val requests = getS_PasswordChangeRequest.ofA(local)
          val updates = requests.get(reqId).toList
            .flatMap(req=>LEvent.update(C_PasswordHashOfUser("test",req.hash)))
          txAdd.add(updates)(local)
        })
      ) ++ userName.map(n=>mTags.text("hint",s"signed in as $n")) ++
      List(mTags.divButton("kill-sessions"){ (local:Context) =>
        val sessions = getU_AuthenticatedSession.ofA(local).values.toList.sortBy(_.sessionKey)
        txAdd.add(sessions.flatMap(LEvent.delete))(local)
      }(List(mTags.text("text","kill sessions"))))
    }
  }
}

