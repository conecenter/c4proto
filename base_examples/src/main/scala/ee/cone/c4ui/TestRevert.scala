package ee.cone.c4ui

import ee.cone.c4di._
import ee.cone.c4actor._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.ViewRes

@c4("TestTodoApp") final case class RevertRootView(locationHash: String = "revert")(
  revert: Reverting,
  untilPolicy: UntilPolicy,
  exampleTagsProvider: ExampleTagsProvider,
  makeSavepointReceiver: MakeSavepointReceiver,
  revertToSavepointReceiver: RevertToSavepointReceiver,
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context],
) extends ByLocationHashView {
  def view: Context => ViewRes = untilPolicy.wrap { local =>

    exampleTags.button("makeSavepoint",
      activate = makeSavepointReceiver,
      caption = "makeSavepoint",
    ).toChildPair[OfDiv] ::
    revert.getSavepoint(local).toList.map(offset=>
      exampleTags.button("revertToSavepoint",
        activate = revertToSavepointReceiver,
        caption = s"revertTo $offset",
      ).toChildPair[OfDiv]
    )
  }
}

@c4("TestTodoApp") final case class MakeSavepointReceiver()(
  revert: Reverting,
) extends Receiver[Context] {
  def receive: Handler = message => revert.makeSavepoint
}

@c4("TestTodoApp") final case class RevertToSavepointReceiver()(
  revert: Reverting,
) extends Receiver[Context] {
  def receive: Handler = message => revert.revertToSavepoint
}