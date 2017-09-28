package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{ModelAccessFactory, _}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.{ChildPair, OfDiv, TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes

class TestTodoApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with UMLClientsApp with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
{
  override def protocols: List[Protocol] = TestTodoProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestTodoAssemble ::
    new FromAlienTaskAssemble("/react-app.html") ::
    super.assembles
}

@protocol object TestTodoProtocol extends Protocol {
  @Id(0x0001) case class TodoTask(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}


  //marker [class] @Id .field
  // @Id lazy val

/*
@Id() case class OrigDeepDateRange(
  @Id() srcId:     SrcId, // srcId = hash (userId/SessionId + filterId + objSrcId)
  @Id() filterId:  Int,
  @Id() objSrcId:  Option[SrcId],
  @Id() dateFrom:  Long,
  @Id() dateTo:    Long
)


object CommonNames {
  def name1 = translatable en "aaa"
}

object MyFilter {
  @Id() flt1 = deepDateRange scale minute userLabel en "sss1" ru "sss1"
  @Id() flt2 = deepDateRange
  @Id() flt3 = deepDateRange userLabel CommonNames.name1
}

pk flt: @id + Option[SrcId]

list1 .... {
type Row
def filters = MyFilter.flt1.by(srcid).bind(_.issue) :: MyFilter.flt2.bind(_.closed) :: MyFilter.flt3.bind(_.started) :: Nil


MyFilter.flt1.by(pk).get.dateFrom
}

list2 .... {
  def filters = fltBind(MyFilter.flt2, _.started) :: Nil // by DL

  def filters = {
     val flts = SessionDataByPK(classOf[MyFilter])(pk)
     fltBind(flts.flt2, _.started) :: Nil
  } // by SK

  MyFilter.flt1.by(pk).get.dateFrom // by DL
  SessionDataByPK(classOf[MyFilter])(pk).flt1.dateFrom // by SK
}

filterAccess pk pk to flt1

default -- in handler
trait FilterAccessFactory {
  def pk(key: SrcId): FilterAccessFactory
  def to[P](filter: Filter[P])(default: SrcId=>P): Access[P]
}

*/

/*

//extends AbstractLens[Context,FilterHandler[W]]
trait FilterHandler[W] {

}

class FilterHandlerRegistryImpl(handlers: Map) extends FilterHandlerRegistry[SharedComponentKey] {
  def get[P](filter: Filter[P]) = ???

}
*/



object SessionAttr {
  def apply[B](id: Id, cl: Class[B], values: MetaAttr*): SessionAttr[B] =
    SessionAttr(cl.getName, id.id, "", metaList = values.toList)
}
case class SessionAttr[By](
  className: String, id: Long, pk: SrcId, metaList: List[MetaAttr]
)

case object DefaultModelsKey extends SharedComponentKey[Map[String,SrcId⇒Object]]

//impl,reg
trait SessionAttrAccessFactory {
  def to[P](attr: SessionAttr[P]): Context⇒Option[Access[P]]
}

//impl,reg
case object AccessViewsKey extends SharedComponentKey[Map[String,Access[_]⇒Context⇒List[ChildPair[OfDiv]]]]

trait AccessViewRegistry extends AccessView[Object] {
  def set[P](cl: Class[P], view: AccessView[P]): List[Injectable]
}

trait AccessView[P] {
  def view(access: Access[P]): Context⇒List[ChildPair[OfDiv]]
}

//impl,reg
trait FilterPredicateFactory {
  def create[Model](): FilterPredicate[Model]
}
abstract class FilterPredicateKey[By,Field] extends SharedComponentKey[By⇒Field⇒Boolean]
trait FilterPredicate[Model] {
  def add[By,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: FilterPredicateKey[By,Field]): FilterPredicate[Model]
  def keys: List[SessionAttr[Object]]
  def of: Context ⇒ Model ⇒ Boolean
}

////////

import CommonFilterProtocol._
object CommonFilterProtocol extends Protocol {//to proto
  case class DateBefore(srcId: SrcId, value: Option[Long])
  case class Contains(srcId: SrcId, value: String)
}
class CommonFilterInject extends ToInject {//reg
  def toInject: List[Injectable] =
    DefaultModelsKey.set(Map(classOf[DateBefore].getName→(pk⇒DateBefore(pk,None)))) :::
      DefaultModelsKey.set(Map(classOf[Contains].getName→(pk⇒Contains(pk,""))))
}
object CommonFilterKeys {//impl,reg
  implicit case object DateBeforePredicateKey extends FilterPredicateKey[DateBefore,Long]
  implicit case object ContainsPredicateKey extends FilterPredicateKey[Contains,String]
}



object TestFilterKeys {
  //import CommonFilterKeys._
  import TestTodoAccess._

  lazy val createdAtFlt = SessionAttr(Id(0x6666), classOf[DateBefore], UserLabel en "...")
  lazy val commentsFlt = SessionAttr(Id(0x6667), classOf[Contains], UserLabel en "...")

  /*
  lazy val filters: List[FilterOf[TodoTask]] = List(
    createdAtFlt to createdAt,
    commentsFlt to comments
  )*/

}



/////
trait ByLocationHashViewsApp {
  def byLocationHashViews: List[ByLocationHashView] = Nil
}
trait PublicViewAssembleApp extends AssemblesApp {
  def byLocationHashViews: List[ByLocationHashView]
  override def assembles: List[Assemble] =
    new PublicViewAssemble(byLocationHashViews) :: super.assembles
}

@assemble class PublicViewAssemble(views: List[ByLocationHashView]) extends Assemble {//reg
  type LocationHash = String
  def joinByLocationHash(
    key: SrcId,
    fromAliens: Values[FromAlienTask]
  ): Values[(LocationHash,FromAlienTask)] = for {
    fromAlien ← fromAliens
  } yield fromAlien.locationHash → fromAlien

  def joinPublicView(
    key: SrcId,
    firstborns: Values[Firstborn]
  ): Values[(SrcId,ByLocationHashView)] = for {
    _ ← firstborns
    view ← views
  } yield WithPK(view)

  def join(
    key: SrcId,
    publicViews: Values[ByLocationHashView],
    @by[LocationHash] tasks: Values[FromAlienTask],
  ): Values[(SrcId,View)] = for {
    publicView ← publicViews
    task ← tasks
  } yield WithPK(AssignedPublicView(task.branchKey,publicView))
}
case class AssignedPublicView(branchKey: SrcId, currentView: View) extends View {
  def view: Context ⇒ ViewRes =
    CurrentBranchKey.set(branchKey).andThen(currentView.view)
}

trait ByLocationHashView extends View
case object CurrentBranchKey extends TransientLens[SrcId]("")


import TestTodoAccess._
@fieldAccess object TestTodoAccess {
  lazy val comments: ProdLens[TodoTask,String] =
    ProdLens.of(_.comments, UserLabel en "(comments)")
  lazy val createdAt: ProdLens[TodoTask,Long] =
    ProdLens.of(_.createdAt, UserLabel en "(created at)")
}



trait TestTodoRootViewApp extends ByLocationHashViewsApp {
  def testTags: TestTags[Context]
  def tags: Tags
  def tagStyles: TagStyles
  def modelAccessFactory: ModelAccessFactory
  def filterPredicateFactory: FilterPredicateFactory
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def accessViewRegistry: AccessViewRegistry

  override def byLocationHashViews: List[ByLocationHashView] = TestTodoRootView()(
    testTags,
    tags,
    tagStyles,
    modelAccessFactory,
    filterPredicateFactory,
    sessionAttrAccessFactory,
    accessViewRegistry
  ) :: super.byLocationHashViews
}

case class TestTodoRootView(locationHash: String = "todo")(//reg
  tags: TestTags[Context],
  mTags: Tags,
  styles: TagStyles,
  contextAccess: ModelAccessFactory,
  filterPredicateFactory: FilterPredicateFactory,
  sessionAttrAccess: SessionAttrAccessFactory,
  accessViewRegistry: AccessViewRegistry
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    import mTags._

    val filterPredicate = filterPredicateFactory.create[TodoTask]()
      .add(TestFilterKeys.commentsFlt, comments)
      .add(TestFilterKeys.createdAtFlt, createdAt)

    val filterList = for {
      k ← filterPredicate.keys
      access ← sessionAttrAccess.to(k)(local)
      tag ← accessViewRegistry.view(access)(local)
    } yield tag

    val btnList = List(
      divButton("add")(
        TxAdd(update(TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )

    val todoTasks = ByPK(classOf[TodoTask]).of(local).values
      .filter(filterPredicate.of(local)).toList.sortBy(-_.createdAt)
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
filterKey + mf-lens + pk+local : ?input,?filterPred,?value
trait F[R] {
  def bind[B,C](FilterKey[B,R], lens: ProdLens[C,R]): BoundFilterKey[B,C]

}
class BoundFilterKey[By,Model,Field](filterKey: SessionAttr[By,Field], lens: ProdLens[Model,Field]) {
  def filter(filterAccess: ): Model⇒Boolean = {
    val uHandler = ???
        val handler: Field⇒Boolean = uHandler
    model ⇒ handler(lens.of(model))
  }
}
*/


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