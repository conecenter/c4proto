package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.{AbstractComponents, AutoMixer, Component, TypeKey}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object ComponentRegistry {
  def isRegistry: Component=>Boolean = {
    val clName = classOf[AbstractComponents].getName
    c => c.in match {
      case Seq(inKey) if inKey.clName == clName => true
      case _ => false
    }
  }
  def apply(app: AbstractComponents): ComponentRegistry =
    Single(Single(app.components.filter(isRegistry).distinct).create(Seq(app)))
      .asInstanceOf[ComponentRegistry]

  def getAll(mixer: AutoMixer): List[Component] = { // todo move to impl?
    import scala.concurrent.ExecutionContext.Implicits.global
    def mixersOf( mixer: AutoMixer, was: (Set[AutoMixer], List[AutoMixer])): (Set[AutoMixer], List[AutoMixer]) = {
      val (wasS, wasL) = was
      if (wasS(mixer)) was else mixer.dependencies.foldRight((wasS + mixer, mixer :: wasL))(mixersOf)
    }
    val (_, mixers) = mixersOf(mixer, (Set.empty, Nil))
    val debug = Option(System.getenv("C4DEBUG_COMPONENTS")).nonEmpty
    if(debug) println(s"mixers found: ${mixers.size}")
    val componentsF = Future.sequence(mixers.map(v => Future(v.getComponents()))).map(_.flatten)
    val components = Await.result(componentsF, Duration.Inf)
    if(debug) println(s"mixer components found ${components.size}")
    components
  }
}

trait ComponentRegistry {
  def resolveKey(key: TypeKey): DeferredSeq[Any]
  def resolve[T](cl: Class[T], args: Seq[TypeKey]): DeferredSeq[T]
  def components: Seq[Component]
}

trait DeferredSeq[+T] {
  def value: Seq[T]
}

case class StrictTypeKey[T](value: TypeKey)