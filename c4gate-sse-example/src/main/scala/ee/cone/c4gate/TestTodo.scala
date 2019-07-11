package ee.cone.c4gate

import java.util.UUID
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4gate.CommonFilterProtocol._
import ee.cone.c4gate.TestTodoProtocol.B_TodoTask
import ee.cone.c4proto._
import ee.cone.c4ui._
import ee.cone.c4vdom.{TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes

class TestTodoApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with UIApp
  with TestTagsApp
  with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
  with PublicViewAssembleApp
  with CommonFilterInjectApp
  with CommonFilterPredicateFactoriesApp
  with FilterPredicateBuilderApp
  with ModelAccessFactoryApp
  with AccessViewApp
  with DateBeforeAccessViewApp
  with ContainsAccessViewApp
  with SessionAttrApp
  with MortalFactoryApp
  with AvailabilityApp
  with TestTodoRootViewApp
{

  override def protocols: List[Protocol] =
    CommonFilterProtocol :: TestTodoProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new FromAlienTaskAssemble("/react-app.html") ::
    super.assembles
  override def longTxWarnPeriod: Long = 10L
}

@protocol(TestCat) object TestTodoProtocolBase   {
  @Id(0x0001) case class B_TodoTask(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}

import TestTodoAccess._
@fieldAccess object TestTodoAccessBase {
  lazy val comments: ProdLens[B_TodoTask,String] =
    ProdLens.of(_.comments, UserLabel en "(comments)")
  lazy val createdAt: ProdLens[B_TodoTask,Long] =
    ProdLens.of(_.createdAt, UserLabel en "(created at)")
  lazy val createdAtFlt =
    SessionAttr(Id(0x0006), classOf[B_DateBefore], UserLabel en "(created before)")
  lazy val commentsFlt =
    SessionAttr(Id(0x0007), classOf[B_Contains], IsDeep, UserLabel en "(comments contain)")
}

trait TestTodoRootViewApp extends ByLocationHashViewsApp {
  def testTags: TestTags[Context]
  def tags: Tags
  def tagStyles: TagStyles
  def modelAccessFactory: ModelAccessFactory
  def filterPredicateBuilder: FilterPredicateBuilder
  def commonFilterConditionChecks: CommonFilterConditionChecks
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def accessViewRegistry: AccessViewRegistry
  def untilPolicy: UntilPolicy

  private lazy val testTodoRootView = TestTodoRootView()(
    testTags,
    tags,
    tagStyles,
    modelAccessFactory,
    filterPredicateBuilder,
    commonFilterConditionChecks,
    accessViewRegistry,
    untilPolicy
  )

  override def byLocationHashViews: List[ByLocationHashView] =
    testTodoRootView :: super.byLocationHashViews
}

case class TestTodoRootView(locationHash: String = "todo")(
  tags: TestTags[Context],
  mTags: Tags,
  styles: TagStyles,
  contextAccess: ModelAccessFactory,
  filterPredicates: FilterPredicateBuilder,
  commonFilterConditionChecks: CommonFilterConditionChecks,
  accessViewRegistry: AccessViewRegistry,
  untilPolicy: UntilPolicy
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    import mTags._
    import commonFilterConditionChecks._
    val filterPredicate = filterPredicates.create[B_TodoTask](local)
      .add(commentsFlt, comments)
      .add(createdAtFlt, createdAt)

    val filterList = for {
      access ← filterPredicate.accesses
      tag ← accessViewRegistry.view(access)(local)
    } yield tag
    // filterPredicate.accesses.flatMap { case a if a.initialValue => List(a to sub1, a to sub2) case a => List(a) }

    val btnList = List(
      divButton("add")(
        TxAdd(update(B_TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )

    val todoTasks = ByPK(classOf[B_TodoTask]).of(local).values
      .filter(filterPredicate.condition.check).toList.sortBy(-_.createdAt)
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
    S_BranchResult --> BranchRel-s
    S_BranchResult [prev] + BranchRel-s --> BranchTask [decode]
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

S_BranchResult --> BranchRel-s --> BranchTask --> [custom] --> BranchHandler --> TxTransform
*/