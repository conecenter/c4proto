package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4gate.CommonFilterProtocol._
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto._
import ee.cone.c4ui._
import ee.cone.c4vdom.{TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes

class TestTodoApp extends ServerApp
  with `The EnvConfigImpl` with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with UIApp
  with `The TestTagsImpl`
  with `The NoAssembleProfiler`
  with ManagementApp
  with FileRawSnapshotApp
  with `The PublicViewAssemble`
  with CommonFilterInjectApp
  with `The CommonFilterConditionChecksImpl`
  with `The FilterPredicateBuilderImpl`
  with `The ModelAccessFactoryImpl`
  with AccessViewApp
  with `The DateBeforeAccessView`
  with `The ContainsAccessView`
  with SessionAttrApp
  with `The TestTodoRootView`
  with `The ByLocationHashView`
  with `The CommonFilterProtocol`
  with `The TestTodoProtocol`
  with `The ReactAppAssemble`

@protocol object TestTodoProtocol extends Protocol {
  @Id(0x0001) case class TodoTask(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}

import TestTodoAccess._
object TestTodoAccess {
  lazy val createdAtFlt =
    SessionAttr(Id(0x0006), classOf[DateBefore], UserLabel en "(created before)")
  lazy val commentsFlt =
    SessionAttr(Id(0x0007), classOf[Contains], IsDeep, UserLabel en "(comments contain)")
}

@c4component @listed case class TestTodoRootView(locationHash: String = "todo")(
  tags: TestTags,
  mTags: Tags,
  styles: TagStyles,
  contextAccess: ModelAccessFactory,
  filterPredicates: FilterPredicateBuilder,
  commonFilterConditionChecks: CommonFilterConditionChecks,
  sessionAttrAccess: SessionAttrAccessFactory,
  accessViewRegistry: AccessViewRegistry,
  untilPolicy: UntilPolicy,
  //requests: ByUKGetter[SrcId,Request[TodoTask]] @c4key,
  todoTasksKey: ByUKGetter[SrcId,TodoTask] @c4key,
  comments: ProdLens[TodoTask,String] =
    ProdLens.of(_.comments, UserLabel en "(comments)"),
  createdAt: ProdLens[TodoTask,Long] =
    ProdLens.of(_.createdAt, UserLabel en "(created at)")
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    import mTags._
    import commonFilterConditionChecks._
    val filterPredicate = filterPredicates.create[TodoTask]()
      .add(commentsFlt, comments)
      .add(createdAtFlt, createdAt)

    val filterList = for {
      sessionAttr ← filterPredicate.keys
      access ← sessionAttrAccess.to(sessionAttr)(local).toList
      tag ← accessViewRegistry.view(access)(local)
    } yield tag

    val btnList = List(
      divButton("add")(
        TxAdd(update(TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )

    val todoTasks = todoTasksKey.of(local).values
      .filter(filterPredicate.of(local).check).toList.sortBy(-_.createdAt)
    val taskLines = for {
      prod ← todoTasks
      task ← contextAccess to prod
    } yield div(prod.srcId,Nil)(List(
      tags.input(task to comments),
      div("remove",List(styles.width(100),styles.displayInlineBlock))(List(
        divButton("remove")(TxAdd(delete(prod)))(List(text("caption","-")))
      ))
    ))

    List(filterList,btnList,taskLines).flatten
  }
}

/*
branches:
    BranchResult --> BranchRel-s
    BranchResult [prev] + BranchRel-s --> BranchTask [decode]
    ...
    BranchHandler + BranchRel-s + MessageFromAlien-s -> TxTransform
ui:
    FromAlienState --> BranchRel [encode]
    BranchTask --> FromAlienTask [match host etc]
    BranchTask + View --> BranchHandler
    BranchTask + CanvasHandler --> BranchHandler
custom:
    FromAlienTask --> View [match hash]
    BranchTask --> CanvasHandler

BranchResult --> BranchRel-s --> BranchTask --> [custom] --> BranchHandler --> TxTransform
*/