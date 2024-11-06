package ee.cone.c4ui

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor.{Context, LTxAdd, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.c4multi
import ee.cone.c4gate.CurrentSessionKey
import ee.cone.c4vdom.Receiver
import ee.cone.c4vdom.Types.ViewRes
import okio.ByteString

trait ByLocationHashView extends View

@c4assemble("PublicViewAssembleApp") class PublicViewAssembleBase(views: List[ByLocationHashView])   {
  type LocationHash = String
  def joinByLocationHash(
    key: SrcId,
    fromAlien: Each[FromAlienTask]
  ): Values[(LocationHash,FromAlienTask)] = List(fromAlien.locationHash -> fromAlien)

  def joinPublicView(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,ByLocationHashView)] = for {
    view <- views
  } yield WithPK(view)

  def join(
    key: SrcId,
    publicView: Each[ByLocationHashView],
    @by[LocationHash] task: Each[FromAlienTask]
  ): Values[(SrcId,View)] =
    List(WithPK(AssignedPublicView(task.branchKey,task,publicView)))
}

case class AssignedPublicView(branchKey: SrcId, task: FromAlienTask, currentView: View) extends View {
  def view: Context => ViewRes =
    CurrentSessionKey.set(task.fromAlienState.sessionKey).andThen(currentView.view)
}

////

trait ViewUpdater extends Product {
  type Handler = String => Context => ViewAction => LEvents
  def receive: Handler
}
trait ViewAction extends Product

@c4multi("TestTodoApp") final case class UpdatingReceiver(updater: ViewUpdater, action: ViewAction)(
  txAdd: LTxAdd
) extends Receiver[Context] {
  def receive: Handler = m => local => {
    val value = m.body match { case b: ByteString => b.utf8() }
    txAdd.add(updater.receive(value)(local)(action))(local)
  }
}

