package ee.cone.c4gate

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{ModelAccessFactory, QAdapterRegistry, _}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto._
import ee.cone.c4ui._
import ee.cone.c4vdom.{ChildPair, OfDiv, TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes
import okio.ByteString

class TestTodoApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with UMLClientsApp with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
  with PublicViewAssembleApp
  with CommonFilterInjectApp
  with CommonFilterPredicateFactoriesApp
  with FilterPredicateBuilderApp
  with ModelAccessFactoryApp
  with AccessViewRegistryApp
  with DateBeforeAccessViewApp
  with ContainsAccessViewApp
  with DefaultModelRegistryApp
  with SessionAttrAccessFactoryImplApp
  with SessionDataAssembleApp
  with MortalFactoryApp
  with TestTodoRootViewApp
{
  override def protocols: List[Protocol] =
    CommonFilterProtocol :: SessionDataProtocol :: TestTodoProtocol :: super.protocols
  override def assembles: List[Assemble] =
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

////////////////////////////////////////////////////////////////////////////////


import ee.cone.c4actor.LifeTypes.Alive
import SessionDataProtocol.RawSessionData
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4proto.{Id, Protocol, protocol}


@protocol object SessionDataProtocol extends Protocol {
  @Id(0x0066) case class RawSessionData(
    @Id(0x0061) srcId: String,
    @Id(0x0067) sessionKey: String,
    @Id(0x0068) domainSrcId: String,
    @Id(0x0069) fieldId: Long,
    @Id(0x0064) valueTypeId: Long,
    @Id(0x0065) value: okio.ByteString
  )
}

//todo user level with expiration
/*
@Id(0x0058) userName: String,
@Id until: Long
  def joinRaw(
    srcId: SrcId,
    rawDataValues: Values[RawSessionData]
  ): Values[(SrcId, SessionData)] = for {
    r ← rawDataValues
    adapter ← registry.byId.get(r.valueTypeId)
  } yield WithPK(SessionData(r.srcId, r, adapter.decode(r.value.toByteArray)))

  def joinUserLife(
    key: SrcId,
    @by[SessionKey] sessionDataValues: Values[RawSessionData]
  ): Values[(Alive, RawSessionData)] = for {
    sessionData ← sessionDataValues if sessionData.sessionKey.isEmpty
  } yield WithPK(sessionData)
*/

case class SessionData(srcId: String, orig: RawSessionData, value: Product)

trait SessionDataAssembleApp extends AssemblesApp {
  def mortal: MortalFactory

  override def assembles: List[Assemble] =
    SessionDataAssembles(mortal) ::: super.assembles
}

object SessionDataAssembles {
  def apply(mortal: MortalFactory): List[Assemble] =
    mortal(classOf[RawSessionData]) :: new SessionDataAssemble :: Nil
}

@assemble class SessionDataAssemble extends Assemble {
  type SessionKey = SrcId

  def joinBySessionKey(
    srcId: SrcId,
    sessionDataValues: Values[RawSessionData]
  ): Values[(SessionKey, RawSessionData)] = for {
    sd ← sessionDataValues
  } yield sd.sessionKey → sd

  def joinSessionLife(
    key: SrcId,
    sessions: Values[FromAlienState],
    @by[SessionKey] sessionDataValues: Values[RawSessionData]
  ): Values[(Alive, RawSessionData)] = for {
    session ← sessions
    sessionData ← sessionDataValues
  } yield WithPK(sessionData)
}

trait SessionAttrAccessFactoryImplApp {
  def qAdapterRegistry: QAdapterRegistry
  def defaultModelRegistry: DefaultModelRegistry
  def modelAccessFactory: ModelAccessFactory

  lazy val sessionAttrAccessFactory: SessionAttrAccessFactory =
    new SessionAttrAccessFactoryImpl(qAdapterRegistry,defaultModelRegistry,modelAccessFactory)
}
class SessionAttrAccessFactoryImpl(
  registry: QAdapterRegistry,
  defaultModelRegistry: DefaultModelRegistry,
  modelAccessFactory: ModelAccessFactory
) extends SessionAttrAccessFactory {
  def to[P<:Product](attr: SessionAttr[P]): Context⇒Option[Access[P]] = {
    val adapter = registry.byName(classOf[RawSessionData].getName)
    def genPK(request: RawSessionData): String =
      UUID.nameUUIDFromBytes(adapter.encode(request)).toString
    val lens = ProdLens[RawSessionData,P](attr.metaList)(
      rawData ⇒ registry.byId(rawData.valueTypeId).decode(rawData.value).asInstanceOf[P],
      value ⇒ rawData ⇒ {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        rawData.copy(valueTypeId = valueAdapter.id, value = byteString)
      }
    )
    val byPK = ByPK(classOf[RawSessionData])
    local ⇒ {
      val sessionKey = CurrentSessionKey.of(local)
      val request: RawSessionData = RawSessionData(
        srcId = "",
        sessionKey = sessionKey,
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )
      val pk = genPK(request)//val deepPK = genPK(request.copy(sessionKey=""))
      val value = byPK.of(local).getOrElse(pk,{
        val model = defaultModelRegistry.raw[P](attr.className).of(local)(pk)
        lens.set(model)(request.copy(srcId=pk))
      })
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }
}

////////

object SessionAttr {
  def apply[B](id: Id, cl: Class[B], values: MetaAttr*): SessionAttr[B] =
    SessionAttr(
      className = cl.getName,
      id = id.id,
      pk = "",
      metaList = NameMetaAttr(s"${id.id}") :: values.toList
    )
}
case class SessionAttr[+By](
  className: String, id: Long, pk: SrcId, metaList: List[MetaAttr]
){
  def withPK(nPK: SrcId): SessionAttr[By] = copy(pk=nPK)
}

trait SessionAttrAccessFactory {
  def to[P<:Product](attr: SessionAttr[P]): Context⇒Option[Access[P]]
}

////////

trait AccessViewRegistry {
  def to[P](cl: Class[P]): InjectableGetter[Context,AccessView[P]]
  def view[P](access: Access[P]): Context⇒List[ChildPair[OfDiv]]
}
trait AccessView[P] {
  def view(access: Access[P]): Context⇒List[ChildPair[OfDiv]]
}

trait DefaultModelRegistry {
  def to[P<:Product](cl: Class[P]): InjectableGetter[Context,SrcId⇒P]
  def raw[P<:Product](className: String): InjectableGetter[Context,SrcId⇒P]
}

////////

trait DefaultModelRegistryApp {
  lazy val defaultModelRegistry: DefaultModelRegistry = DefaultModelRegistryImpl
}
object DefaultModelRegistryImpl extends DefaultModelRegistry {
  def to[P<:Product](cl: Class[P]): InjectableGetter[Context,SrcId⇒P] = raw[P](cl.getName)
  def raw[P<:Product](className: String): InjectableGetter[Context,SrcId⇒P] =
    DefaultModelKey[P](className)
}
case object DefaultModelsKey extends SharedComponentKey[Map[String,SrcId⇒Product]]
case class DefaultModelKey[P<:Product](key: String) extends InjectableGetter[Context,SrcId⇒P] {
  def set: (SrcId⇒P) ⇒ List[Injectable] = value ⇒ DefaultModelsKey.set(Map(key→value))
  def of: Context ⇒ SrcId⇒P = local ⇒ DefaultModelsKey.of(local)(key).asInstanceOf[SrcId⇒P]
}

trait AccessViewRegistryApp {
  lazy val accessViewRegistry: AccessViewRegistry = AccessViewRegistryImpl
}
object AccessViewRegistryImpl extends AccessViewRegistry {
  def to[P](cl: Class[P]): InjectableGetter[Context,AccessView[P]] =
    AccessViewKey[P](cl.getName)
  def view[P](access: Access[P]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ AccessViewsKey.of(local)(access.initialValue.getClass.getName).asInstanceOf[AccessView[P]].view(access)(local)
}
case object AccessViewsKey extends SharedComponentKey[Map[String,AccessView[_]]]
case class AccessViewKey[P](key: String) extends InjectableGetter[Context,AccessView[P]] {
  def set: AccessView[P] ⇒ List[Injectable] = value ⇒ AccessViewsKey.set(Map(key→value))
  def of: Context ⇒ AccessView[P] = local ⇒ AccessViewsKey.of(local)(key).asInstanceOf[AccessView[P]]
}

////////

trait FilterPredicateBuilder {
  def create[Model](): FilterPredicate[Model]
}
trait FilterPredicateFactory[By,Field] {
  def create(by: By): Field⇒Boolean
}
trait FilterPredicate[Model] {
  def add[By<:Product,Field](filterKey: SessionAttr[By], lens: ProdLens[Model,Field])(implicit c: FilterPredicateFactory[By,Field]): FilterPredicate[Model]
  def keys: List[SessionAttr[Product]]
  def of: Context ⇒ Model ⇒ Boolean
}

////////

trait FilterPredicateBuilderApp {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  lazy val filterPredicateBuilder: FilterPredicateBuilder =
    new FilterPredicateBuilderImpl(sessionAttrAccessFactory)
}

class FilterPredicateBuilderImpl(
  sessionAttrAccessFactory: SessionAttrAccessFactory
) extends FilterPredicateBuilder {
  def create[Model](): FilterPredicate[Model] =
    EmptyFilterPredicate[Model]()(sessionAttrAccessFactory)
}

abstract class AbstractFilterPredicate[Model] extends FilterPredicate[Model] {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def add[SBy<:Product,SField](filterKey: SessionAttr[SBy], lens: ProdLens[Model,SField])(
    implicit c: FilterPredicateFactory[SBy,SField]
  ): FilterPredicate[Model] =
    FilterPredicateImpl(this,filterKey,lens)(sessionAttrAccessFactory,c)
}

case class EmptyFilterPredicate[Model]()(
  val sessionAttrAccessFactory: SessionAttrAccessFactory
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = Nil
  def of: Context ⇒ Model ⇒ Boolean = local ⇒ model ⇒ true
}

case class FilterPredicateImpl[Model,By<:Product,Field](
  next: FilterPredicate[Model],
  filterKey: SessionAttr[By],
  lens: ProdLens[Model,Field]
)(
  val sessionAttrAccessFactory: SessionAttrAccessFactory,
  filterPredicateFactory: FilterPredicateFactory[By,Field]
) extends AbstractFilterPredicate[Model] {
  def keys: List[SessionAttr[Product]] = filterKey :: next.keys
  def of: Context ⇒ Model ⇒ Boolean = local ⇒ {
    val by = sessionAttrAccessFactory.to(filterKey)(local).get.initialValue
    val colPredicate = filterPredicateFactory.create(by)
    val nextPredicate = next.of(local)
    model ⇒ colPredicate(lens.of(model)) && nextPredicate(model)
  }
}

////////

@protocol object CommonFilterProtocol extends Protocol {
  @Id(0x0006) case class DateBefore(
    @Id(0x0001) srcId: String,
    @Id(0x0002) value: Option[Long]
  )
  @Id(0x0007) case class Contains(
    @Id(0x0001) srcId: String,
    @Id(0x0002) value: String
  )
}

////////

//todo access Meta from here, ex. precision
//todo ask if predicate active ?Option[Field=>Boolean]

import CommonFilterProtocol._
object DateBeforePredicateFactory extends FilterPredicateFactory[DateBefore,Long] {
  def create(by: DateBefore): Long⇒Boolean = value ⇒ by.value forall (_>value)
}
object ContainsPredicateFactory extends FilterPredicateFactory[Contains,String] {
  def create(by: Contains): String⇒Boolean = value ⇒ value contains by.value
}
object CommonFilterPredicateFactoriesImpl extends CommonFilterPredicateFactories {
  lazy val dateBeforePredicateFactory: FilterPredicateFactory[DateBefore,Long] = DateBeforePredicateFactory
  lazy val containsPredicateFactory: FilterPredicateFactory[Contains,String] = ContainsPredicateFactory
}
trait CommonFilterPredicateFactoriesApp {
  lazy val commonFilterPredicateFactories: CommonFilterPredicateFactories = CommonFilterPredicateFactoriesImpl
}

trait CommonFilterInjectApp extends ToInjectApp {
  def defaultModelRegistry: DefaultModelRegistry
  override def toInject: List[ToInject] =
    new CommonFilterInject(defaultModelRegistry) :: super.toInject
}
class CommonFilterInject(defaultModelRegistry: DefaultModelRegistry) extends ToInject {
  def toInject: List[Injectable] = List(
    defaultModelRegistry.to(classOf[DateBefore]).set(DateBefore(_,None)),
    defaultModelRegistry.to(classOf[Contains]).set(Contains(_,""))
  ).flatten
}

trait DateBeforeAccessViewApp extends ToInjectApp {
  def testTags: TestTags[Context]
  def accessViewRegistry: AccessViewRegistry
  private lazy val dateBeforeAccessView =
    new DateBeforeAccessView(testTags,accessViewRegistry)
  override def toInject: List[ToInject] = dateBeforeAccessView :: super.toInject
}
trait ContainsAccessViewApp extends ToInjectApp {
  def testTags: TestTags[Context]
  def accessViewRegistry: AccessViewRegistry
  private lazy val containsAccessView =
    new ContainsAccessView(testTags,accessViewRegistry)
  override def toInject: List[ToInject] = containsAccessView :: super.toInject
}
class DateBeforeAccessView(
  testTags: TestTags[Context], accessViewRegistry: AccessViewRegistry
) extends AccessView[DateBefore] with ToInject {
  def toInject: List[Injectable] =
    accessViewRegistry.to(classOf[DateBefore]).set(this)
  def view(access: Access[DateBefore]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.dateInput(access to DateBeforeAccess.value))
}
class ContainsAccessView(
  testTags: TestTags[Context], accessViewRegistry: AccessViewRegistry
) extends AccessView[Contains] with ToInject {
  def toInject: List[Injectable] =
    accessViewRegistry.to(classOf[Contains]).set(this)
  def view(access: Access[Contains]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.input(access to ContainsAccess.value))
}

/////

@fieldAccess object DateBeforeAccess {
  lazy val value: ProdLens[DateBefore,Option[Long]] = ProdLens.of(_.value)
}
@fieldAccess object ContainsAccess {
  lazy val value: ProdLens[Contains,String] = ProdLens.of(_.value)
}

trait CommonFilterPredicateFactories {
  implicit def dateBeforePredicateFactory: FilterPredicateFactory[DateBefore,Long]
  implicit def containsPredicateFactory: FilterPredicateFactory[Contains,String]
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

@assemble class PublicViewAssemble(views: List[ByLocationHashView]) extends Assemble {
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
    @by[LocationHash] tasks: Values[FromAlienTask]
  ): Values[(SrcId,View)] = for {
    publicView ← publicViews
    task ← tasks
  } yield WithPK(AssignedPublicView(task.branchKey,task,publicView))
}
case class AssignedPublicView(branchKey: SrcId, task: FromAlienTask, currentView: View) extends View {
  def view: Context ⇒ ViewRes = Function.chain(Seq(
    CurrentBranchKey.set(branchKey),
    CurrentSessionKey.set(task.fromAlienState.sessionKey)
  )).andThen(currentView.view)
}

trait ByLocationHashView extends View
case object CurrentBranchKey extends TransientLens[SrcId]("")
case object CurrentSessionKey extends TransientLens[SrcId]("")

////

object TestFilterKeys {
  lazy val createdAtFlt = SessionAttr(Id(0x0006), classOf[DateBefore], UserLabel en "(created before)")
  lazy val commentsFlt = SessionAttr(Id(0x0007), classOf[Contains], IsDeep, UserLabel en "(comments contain)")
}

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
  def filterPredicateBuilder: FilterPredicateBuilder
  def commonFilterPredicateFactories: CommonFilterPredicateFactories
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def accessViewRegistry: AccessViewRegistry
  def untilPolicy: UntilPolicy


  private lazy val testTodoRootView = TestTodoRootView()(
    testTags,
    tags,
    tagStyles,
    modelAccessFactory,
    filterPredicateBuilder,
    commonFilterPredicateFactories,
    sessionAttrAccessFactory,
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
  commonFilterPredicateFactories: CommonFilterPredicateFactories,
  sessionAttrAccess: SessionAttrAccessFactory,
  accessViewRegistry: AccessViewRegistry,
  untilPolicy: UntilPolicy
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    import mTags._
    import commonFilterPredicateFactories._
    val filterPredicate = filterPredicates.create[TodoTask]()
      .add(TestFilterKeys.commentsFlt, comments)
      .add(TestFilterKeys.createdAtFlt, createdAt)

    val filterList = for {
      k ← filterPredicate.keys
      access ← sessionAttrAccess.to(k)(local).toList
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