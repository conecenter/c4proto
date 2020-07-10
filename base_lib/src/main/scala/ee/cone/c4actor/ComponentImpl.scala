package ee.cone.c4actor

import ee.cone.c4assemble.{ByPriority, Single}
import ee.cone.c4di._

import scala.annotation.tailrec

@c4("BaseApp") final class ByPriorityImpl extends ByPriority {
  def byPriority[K,V](uses: K=>(List[K],List[V]=>V)): List[K] => List[V] =
    new ByPriorityBuilder[K,V](uses).apply
}

case class PriorityState[K,V](map: Map[K,V], values: List[V], inProcess: Set[K], inProcessList: List[K])

class ByPriorityBuilder[K,V](uses: K=>(List[K],List[V]=>V)) {
  private def add(state: PriorityState[K,V], key: K): PriorityState[K,V] =
    if(state.map.contains(key)) state else {
      if (state.inProcess(key)) throw new Exception(s"${state.inProcessList.mkString("\n")} \nhas $key")
      val (useKeys,toValue) = uses(key)
      val deeperState = state.copy(
        inProcess = state.inProcess + key,
        inProcessList = key :: state.inProcessList
      )
      val filled: PriorityState[K, V] = useKeys.foldLeft(deeperState)(add)
      val value = toValue(useKeys.map(filled.map))
      state.copy(map = filled.map + (key->value), values = value :: filled.values)
    }
  def apply(items: List[K]): List[V] =
    items.foldLeft(PriorityState[K,V](Map.empty[K,V],Nil,Set.empty[K],Nil))(add).values
}

case object TemplateTypeKey extends TypeKey {
  private def never(): Nothing = throw new Exception()
  def cl: Class[_] = never()
  def clName: String = never()
  def args: List[TypeKey] = never()
  def alias: String = never()
  def copy(alias: String, args: List[TypeKey]): TypeKey = never()
}

object ComponentRegistryInit {
  private val byPriority: ByPriority = new ByPriorityImpl
  private val debug: Boolean = Option(System.getenv("C4DEBUG_COMPONENTS")).nonEmpty
  type TemplateReg = Map[TypeKeyTemplate, List[ComponentCreatorTemplate]]
  private def checkGroupTemplates(templates: List[ComponentCreatorTemplate]): TemplateReg = {
    val templatesWithKeys = templates.map(t=>(t.getKey(TemplateTypeKey),t))
    @tailrec def chk(k: TypeKey, depth: Int): Boolean =
      if(k eq TemplateTypeKey) depth > 0 else k.args match {
        case Seq(arg) => chk(arg,depth+1)
        case _ => false
      }
    val badKeys = templatesWithKeys.filter{ case (k, t) => !chk(k.typeKey, 0) }
    assert(badKeys.isEmpty, s"template keys not supported: $badKeys")
    templatesWithKeys.groupMap(_._1)(_._2)
  }
  private def getCreatorsFromTemplates(staticSimpleCreators: List[ComponentCreator], templateReg: TemplateReg): List[ComponentCreator] = {
    def get(key: TypeKey, wrap: TypeKey=>TypeKeyTemplate): List[ComponentCreator] = {
      val ownCreators = for {
        template <- templateReg.getOrElse(wrap(TemplateTypeKey), Nil)
      } yield template.create(key)
      val innerCreators = key.args match {
        case Seq(arg) => get(arg, k=>wrap(key.copy(args=List(k))))
        case _ => Nil // complex keys not supported
      }
      ownCreators ::: innerCreators
    }
    def getCreatorsFromKeys(keys: List[TypeKeyTemplate]): List[ComponentCreator] = {
      keys.flatMap{
        case FromInputTypeKeyTemplate(typeKey) => get(typeKey, FromInputTypeKeyTemplate)
        case FromOutputTypeKeyTemplate(typeKey) => get(typeKey, FromOutputTypeKeyTemplate)
      }
    }
    def getIOKeys(creators: List[ComponentCreator]): List[TypeKeyTemplate] =
      creators.flatMap { c =>
        FromInputTypeKeyTemplate(c.out) ::
          c.in.map(FromOutputTypeKeyTemplate).toList
      }
    @tailrec def iter(currentCreators: List[ComponentCreator], wasKeys: Set[TypeKeyTemplate], resCreators: List[ComponentCreator]): List[ComponentCreator] = {
      val willKeys = getIOKeys(currentCreators).filterNot(wasKeys)
      val newCreators = getCreatorsFromKeys(willKeys)
      if(newCreators.isEmpty) resCreators else iter(newCreators, wasKeys++willKeys, newCreators ::: resCreators)
    }
    iter(staticSimpleCreators, Set.empty, staticSimpleCreators)
  }

  private trait Decoder[T]{
    def simple(key: TypeKey): T
    def list(key: TypeKey): T
    def key(key: TypeKey): T
  }

  private def decodeTypeKey[T](decoder: Decoder[T]): TypeKey=>T = {
    val listClName: String = classOf[List[_]].getName
    val keyClName: String = classOf[StrictTypeKey[_]].getName
    ({
      case k if k.clName == keyClName => decoder.key(Single(k.args))
      case k if k.clName == listClName => decoder.list(Single(k.args))
      case k => decoder.simple(k)
    })
  }

  private def checkOutKeys(keys: List[TypeKey]): Unit = {
    val decode = decodeTypeKey(new Decoder[Boolean]{
      def simple(key: TypeKey): Boolean = false
      def list(key: TypeKey): Boolean = true
      def key(key: TypeKey): Boolean = true
    })
    val badOuts = keys.filter(decode)
    assert(badOuts.isEmpty, s"bad out keys: $badOuts")
  }

  private def sortCreators(creators: List[ComponentCreator]): List[ComponentCreator] = {
    val creatorByOut = creators.groupBy(_.out)
    byPriority.byPriority[ComponentCreator,ComponentCreator](
      creator=>(creator.in.toList.flatMap(creatorByOut.getOrElse(_,Nil)), _ => creator)
    )(creators).reverse
  }

  private def toTypeKey[T](cl: Class[T]): TypeKey =
    CreateTypeKey(cl,cl.getSimpleName,Nil)

  type Reg = Map[TypeKey,List[Object]]
  private def createComponents(orderedCreators: List[ComponentCreator]): Reg = {
    val decode = decodeTypeKey(new Decoder[Reg=>Object]{
      def simple(key: TypeKey): Reg=>Object =
        res=>Single(res.getOrElse(key,Nil),(l:Seq[Object])=>new Exception(s"non-single component -- $key -- $l"))
      def list(key: TypeKey): Reg=>Object =
        _.getOrElse(key,Nil)
      def key(key: TypeKey): Reg=>Object =
        _=>StrictTypeKey(key)
    })
    val initialReg: Reg = Map(toTypeKey(classOf[ComponentCreator])->orderedCreators)
    orderedCreators.foldLeft(initialReg) { (res, creator) =>
      val newComponents = creator.create(creator.in.map(k => decode(k)(res))).toList
      val willComponents = res.get(creator.out).fold(newComponents)(newComponents ::: _)
      res.updated(creator.out, willComponents)
    }
  }

  private class ComponentRegistryImpl(reg: Reg) extends ComponentRegistry {
    def resolve[T](cl: Class[T]): Seq[T] = reg.getOrElse(toTypeKey(cl),Nil).asInstanceOf[Seq[T]]
  }

  def setup(appComponents: List[Component]): ComponentRegistry = {
    val staticSimpleCreators = appComponents.collect{ case c: ComponentCreator => c }
    val templates = appComponents.collect{ case c: ComponentCreatorTemplate => c }
    val templateReg = checkGroupTemplates(templates)
    val transformedCreators = getCreatorsFromTemplates(staticSimpleCreators, templateReg)
    checkOutKeys(transformedCreators.map(_.out))
    val orderedCreators = sortCreators(transformedCreators)
    if(debug){
      println(s"staticSimpleCreators: ${staticSimpleCreators.size}")
      println(s"templates: ${templates.size}")
      println(s"transformedCreators: ${transformedCreators.size}")
      println(s"orderedCreators: ${orderedCreators.size}")
      orderedCreators.foreach(c=>println(s"component (out: ${c.out}) (in: ${c.in})"))
    }
    val reg = createComponents(orderedCreators)
    val registry = new ComponentRegistryImpl(reg)
    registry.resolve(classOf[DeferredSetup]).foreach(_.run())
    registry
  }

}
