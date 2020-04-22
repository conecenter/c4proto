package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types._
import ee.cone.c4assemble._
import ee.cone.c4di.c4
import Function.tupled

// add rr
object RRTypes {
  type FK = SrcId
}


import scala.reflect.ClassTag
trait RelSrc[From<:Product,Value] {
  def to[To<:Product](implicit ct: ClassTag[To]): EachSubAssemble[To] with Assemble
  def toChildren[To<:Product](implicit ct: ClassTag[To]): EachSubAssemble[RelChildren[To]] with Assemble
}
trait RevRelFactory {
  def rel[From<:Product](lens: ProdLens[From,List[SrcId]])(implicit cf: ClassTag[From]): RelSrc[From,List[SrcId]]
  def rev[To<:Product](lens: ProdLens[To,SrcId])(implicit ct: ClassTag[To]): ValuesSubAssemble[To] with Assemble
}
import RRTypes._
//
class RelSrcImpl[From<:Product,Value](from: Class[From], lens: ProdLens[From,Value], adapter: Value=>List[SrcId]) extends RelSrc[From,Value] {
  def to[To<:Product](implicit ct: ClassTag[To]): EachSubAssemble[To] with Assemble =
    new RelAssemble(from,ct.runtimeClass.asInstanceOf[Class[To]],lens,adapter)()
  def toChildren[To<:Product](implicit ct: ClassTag[To]): EachSubAssemble[RelChildren[To]] with Assemble = {
    val to = ct.runtimeClass.asInstanceOf[Class[To]]
    new RelChildrenAssemble(from,to,new RelAssemble(from,to,lens,adapter)())()
  }
}

@c4("RevRelFactoryImplApp") final class RevRelFactoryImpl extends RevRelFactory {
  def rel[From<:Product](lens: ProdLens[From,List[SrcId]])(implicit cf: ClassTag[From]): RelSrc[From,List[SrcId]] = {
    val from = cf.runtimeClass.asInstanceOf[Class[From]]
    new RelSrcImpl[From,List[SrcId]](from,lens,identity[List[SrcId]])
  }
  def rev[To<:Product](lens: ProdLens[To,SrcId])(implicit ct: ClassTag[To]): ValuesSubAssemble[To] with Assemble =
    new RevAssemble[To,SrcId](ct.runtimeClass.asInstanceOf[Class[To]],lens,v=>List(v))()
}
@assemble class RevAssembleBase[To<:Product,Value](
  classOfTo: Class[To],
  lens: ProdLens[To,Value],
  adapter: Value=>List[SrcId]
)(
  val mergeKeyAddClasses: List[Class[_]] = List(classOfTo),
  val mergeKeyAddString: String = lens.metaList.collect{ case l: NameMetaAttr => l.value }.mkString("-")
) extends ValuesSubAssemble[To] with BasicMergeableAssemble {
  def map(
    key: SrcId,
    to: Each[To]
  ): Values[(FK@ns(mergeKey),To)] = for {
    k <- adapter(lens.of(to))
  } yield k -> to
  def result: Result = tupled(map _)
  //def t: Function
}
case class RelChildren[T<:Product](srcId: SrcId, values: List[T])
case class RelOuterReq(callerId: SrcId)
@assemble class RelAssembleBase[From<:Product,Value,To<:Product](
  classOfFrom: Class[From],
  classOfTo: Class[To],
  val lens: ProdLens[From,Value],
  val adapter: Value=>List[SrcId]
)(
  val mergeKeyAddClasses: List[Class[_]] = List(classOfFrom,classOfTo),
  val mergeKeyAddString: String = lens.metaList.collect{ case l: NameMetaAttr => l.value }.mkString("-")
) extends EachSubAssemble[To] with ValuesSubAssemble[To] with BasicMergeableAssemble {
  def mapReq(
    key: SrcId,
    from: Each[From]
  ): Values[(FK@ns(mergeKey),RelOuterReq)] = {
    val req = RelOuterReq(ToPrimaryKey(from))
    for { k <- adapter(lens.of(from)) } yield k -> req
  }
  def mapResp(
    key: SrcId,
    to: Each[To],
    @by[FK@ns(mergeKey)] request: Each[RelOuterReq]
  ): Values[(FK@ns(mergeKey),To)] =
    List(request.callerId -> to)
  def result: Result = tupled(mapResp _)
}
@assemble class RelChildrenAssembleBase[From<:Product,Value,To<:Product](
  classOfFrom: Class[From],
  classOfTo: Class[To],
  val inner: RelAssemble[From,Value,To]
)(
  val mergeKeyAddClasses: List[Class[_]] = List(classOfFrom,classOfTo),
  val mergeKeyAddString: String = inner.mergeKeyAddString
) extends EachSubAssemble[RelChildren[To]] with BasicMergeableAssemble {
  def join(
    key: SrcId,
    from: Each[From],
    tos: Values[To] = inner.call
  ): Values[(FK@ns(inner.mergeKey),RelChildren[To])] = {
    val tosMap = tos.map(to=>ToPrimaryKey(to)->to).toMap
    val tosList = for {
      k <- inner.adapter(inner.lens.of(from))
      to <- tosMap.get(k)
    } yield to
    List(WithPK(RelChildren(ToPrimaryKey(from),tosList)))
  }
  def result: Result = tupled(join _) //join(_:SrcId,???,???)
}

@c4("JustJoinTestApp") final class JustJoinTestExecutable(
  execution: Execution, contextFactory: ContextFactory
) extends Executable {
  def run(): Unit = {
    IgnoreTestContext(contextFactory.updated(Nil))
    execution.complete()
  }
}

//example

object RRTestItems {
  case class Foo(id: SrcId, bars: List[SrcId])
  case class Bar(id: SrcId)
  case class RichFoo(id: SrcId, bars: List[Bar])
  case class RichFooBar(foo: Foo, bar: Bar)
  case class FooRev(id: SrcId)
  case class BarRev(id: SrcId, foo: SrcId)
}
import RRTestItems._
@fieldAccess object RRTestLensesBase {
  lazy val fooBarsL: ProdLens[Foo,List[SrcId]] = ProdLens.of(_.bars)
  lazy val barFooL: ProdLens[BarRev,SrcId] = ProdLens.of(_.foo)
}
import RRTestLenses._

@c4assemble("RRTest1App") class RRTest1RuleAssembleBase(rr: RevRelFactory)   {
  def join(
    key: SrcId,
    foo: Each[Foo],
    bars: Each[RelChildren[Bar]] = rr.rel(fooBarsL).toChildren[Bar].call
  ): Values[(SrcId,RichFoo)] = List(WithPK(RichFoo(foo.id,bars.values)))
}

@c4assemble("RRTest1App") class RRTest1CheckAssembleBase   {
  type CheckId = String
  def given(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(CheckId,S_Firstborn)] = List("check"->firstborn)
  def givenFoo(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,Foo)] = List(WithPK(Foo("1",List("2","3"))))
  def givenBars(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,Bar)] = List(WithPK(Bar("2")),WithPK(Bar("3")))
  //
  def checkStart(
    key: SrcId,
    foo: Each[RichFoo]
  ): Values[(CheckId,RichFoo)] = List("check"->foo)
  def checkFinish(
    key: SrcId,
    @by[CheckId] firstborn: Each[S_Firstborn],
    @by[CheckId] fooValues: Values[RichFoo]
  ): Values[(SrcId,Product)] = {
    assert(Single.option(fooValues) == Option(RichFoo("1",List(Bar("2"),Bar("3")))))
    println("OK")
    Nil
  }
}

@c4assemble("RRTest2App") class RRTest2RuleAssembleBase(rr: RevRelFactory)   {
  type FooId = SrcId
  def join(
    key: SrcId,
    foo: Each[Foo],
    bar: Each[Bar] = rr.rel(fooBarsL).to[Bar].call
  ): Values[(FooId,RichFooBar)] = List(WithPK(RichFooBar(foo, bar)))
}

@c4assemble("RRTest2App") class RRTest2CheckAssembleBase   {
  type CheckId = String
  def given(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(CheckId,S_Firstborn)] = List("check"->firstborn)
  def givenFoo(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,Foo)] = List(WithPK(Foo("1",List("2","3"))))
  def givenBars(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,Bar)] = List(WithPK(Bar("2")),WithPK(Bar("3")))
  //
  type FooId = SrcId
  def checkStart(
    key: SrcId,
    @by[FooId] fooBar: Each[RichFooBar]
  ): Values[(CheckId,RichFooBar)] = List("check"->fooBar)
  def checkFinish(
    key: SrcId,
    @by[CheckId] firstborn: Each[S_Firstborn],
    @by[CheckId] fooBars: Values[RichFooBar]
  ): Values[(SrcId,Product)] = {
    assert(fooBars.sortBy(_.bar.id).toList == List(
      RichFooBar(Foo("1",List("2","3")),Bar("2")),
      RichFooBar(Foo("1",List("2","3")),Bar("3"))
    ))
    println("OK")
    Nil
  }
}

@c4assemble("RRTest3App") class RRTest3RuleAssembleBase(rr: RevRelFactory)   {
  def join(
    key: SrcId,
    foo: Each[FooRev],
    bars: Values[BarRev] = rr.rev(barFooL).call
  ): Values[(SrcId,RichFoo)] = List(WithPK(RichFoo(foo.id,bars.toList.sortBy(_.id).map(i=>Bar(i.id)))))
}

@c4assemble("RRTest3App") class RRTest3CheckAssembleBase   {
  type CheckId = String
  def given(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(CheckId,S_Firstborn)] = List("check"->firstborn)
  def givenFoo(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,FooRev)] = List(WithPK(FooRev("1")))
  def givenBars(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,BarRev)] = List(WithPK(BarRev("2","1")),WithPK(BarRev("3","1")))
  //
  def checkStart(
    key: SrcId,
    foo: Each[RichFoo]
  ): Values[(CheckId,RichFoo)] = List("check"->foo)
  def checkFinish(
    key: SrcId,
    @by[CheckId] firstborn: Each[S_Firstborn],
    @by[CheckId] fooValues: Values[RichFoo]
  ): Values[(SrcId,Product)] = {
    assert(Single.option(fooValues) == Option(RichFoo("1",List(Bar("2"),Bar("3")))))
    println("OK")
    Nil
  }
}
