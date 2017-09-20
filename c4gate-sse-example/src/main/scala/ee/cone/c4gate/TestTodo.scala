package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, fieldAccess}
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
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
*/

/*
trait Filter[P] {
  def idType[P](id: Long, cl: Class[P]): Filter[P] = new Filter[P] {}
}
object Filter {
  def meta[P](v: Any) = new Filter[P] {}
}
case class TodoTask(comments: String)
@idTypes object TestGroup {
  @Id(0x6666) def test: Filter[TodoTask] = Filter meta ???
}
*/

@assemble class TestTodoAssemble extends Assemble {
  def joinView(
    key: SrcId,
    fromAliens: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      fromAlien ← fromAliens;
      view ← Option(fromAlien.locationHash).collect{
        case "todo" ⇒ TestTodoRootView(fromAlien.branchKey)
      }
    ) yield WithPK(view)
}

import TestTodoAccess._
@fieldAccess object TestTodoAccess {
  lazy val comments: ProdLens[TodoTask,String] = ProdLens.of(_.comments, UserLabel en "(created at)")
}

case class TestTodoRootView(branchKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local)
    val mTags = TagsKey.of(local)
    val contextAccess = ModelAccessFactoryKey.of(local)
    import mTags._
    val todoTasks = ByPK(classOf[TodoTask]).of(local).values.toList.sortBy(-_.createdAt)
    //val input = tags.input()
    //@fieldAccess
    val taskLines = for {
      prod ← todoTasks
      task ← contextAccess to prod
    } yield div(prod.srcId,Nil)(
      tags.input(task to comments) ::
        divButton("remove")(TxAdd(delete(prod)))(List(text("caption","-"))) :: Nil
    )
    val btnList = List(
      divButton("add")(
        TxAdd(update(TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )
    List(btnList,taskLines).flatten
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