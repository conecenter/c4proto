package ee.cone.c4ui

import ee.cone.c4di._
import ee.cone.c4actor._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.ViewRes

@c4tags("TestTodoApp") trait RevertRootViewTags[C] {
  @c4el("ExampleReverting") def reverting(
    key: String, makeSavepoint: Receiver[C], revertToSavepoint: Receiver[C], offset: String,
  ): ToChildPair
}

@c4("TestTodoApp") final case class RevertRootView(locationHash: String = "revert")(
  revert: Reverting, untilPolicy: UntilPolicy, revertRootViewTagsProvider: RevertRootViewTagsProvider,
  updatingReceiverFactory: UpdatingReceiverFactory,
)(
  tags: RevertRootViewTags[Context] = revertRootViewTagsProvider.get[Context],
) extends ByLocationHashView with ViewUpdater {
  import RevertRootView._
  val rc: ViewAction => Receiver[Context] = updatingReceiverFactory.create(this, _)
  def view: Context => ViewRes = untilPolicy.wrap { local =>
    val res = tags.reverting(
      key = "reverting", makeSavepoint = rc(MakeSavepoint()), revertToSavepoint = rc(RevertToSavepoint()),
      offset = revert.getSavepoint(local).toList.mkString
    )
    List(res.toChildPair)
  }
  def receive: Handler = value => local => {
    case MakeSavepoint() => revert.makeSavepoint
    //todo case RevertToSavepoint() => revert.revertToSavepoint(local)
  }
}
object RevertRootView {
  private case class MakeSavepoint() extends ViewAction
  private case class RevertToSavepoint() extends ViewAction
}
