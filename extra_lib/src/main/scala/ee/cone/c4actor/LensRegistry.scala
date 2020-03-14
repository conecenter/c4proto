package ee.cone.c4actor

import ee.cone.c4actor.hashsearch.index.dynamic.{DynamicIndexModelsApp, DynamicIndexModelsProvider, HashSearchIdGeneration, ProductWithId}
import ee.cone.c4di.{Component, ComponentsApp, c4, provide}


trait ProdLensesApp extends ComponentsApp {
  import ComponentProvider.provide
  private lazy val lensListComponent =
    provide(classOf[ProdLensListProvider], ()=>Seq(ProdLensListProvider(getterList)))
  override def components: List[Component] = lensListComponent :: super.components
  def getterList: List[ProdGetter[_, _]] = Nil
}

case class ProdLensListProvider(values: List[ProdGetter[_, _]])

trait LensRegistryApp {
  def lensRegistry: LensRegistry
}

trait LensRegistryMixBase extends ComponentProviderApp with ProdLensesApp with DynamicIndexModelsApp{
  def lensRegistry: LensRegistry = resolveSingle(classOf[LensRegistry])
}

@c4("LensRegistryMix") class LensRegistryProvider(
  lensListProviders: List[ProdLensListProvider],
  dynIndexModelProviders: List[DynamicIndexModelsProvider],
) {
  private def getterList = lensListProviders.flatMap(_.values)
  private def dynIndexModels = dynIndexModelProviders.flatMap(_.values)
  @provide def get: Seq[LensRegistry] = Seq{
    lensIntegrityCheck()
    LensRegistryImpl(getterList.distinct, dynIndexModels.distinct)
  }

  private def lensIntegrityCheck(): Unit = {
    val errors = getterList.distinct.groupBy(prodLens => prodLens.metaList.collect { case a: NameMetaAttr => a }
      .map(_.value) match {
      case Nil => FailWith.apply(s"Lens without name in LensRegistryImpl: $prodLens")
      case a => a
    }
    ).filter(_._2.size > 1)
    if (errors.nonEmpty)
      FailWith.apply(s"Non unique lens name: $errors")
  }
}

trait LensRegistry {
  def getByClasses[Model, Field](modeClName: String, fieldClName: String): List[ProdGetter[Model, Field]]

  def getByCommonPrefix[Model, Field](commonPrefix: String): Option[ProdGetter[Model, Field]]
}

case class LensRegistryImpl(getterList: List[ProdGetter[_, _]], models: List[ProductWithId[_ <: Product]]) extends LensRegistry with HashSearchIdGeneration {
  private def getNames(prodLens: ProdGetter[_, _]): List[String] = prodLens.metaList.collect { case a: NameMetaAttr => a }
    .map(_.value) match {
    case Nil => FailWith.apply(s"Lens without name in LensRegistryImpl: $prodLens, supply NameMetaAttr")
    case a => a
  }

  private def getClasses(prodLens: ProdGetter[_, _]): (String, String) = prodLens.metaList.collectFirst { case a: ClassesAttr => a } match {
    case None => FailWith.apply(s"Lens without modelName in LensRegistryImpl: $prodLens, supply ClassAttr")
    case Some(str) => (str.modelClName, str.fieldClName)
  }

  lazy val byClassesMap: Map[(String, String), List[ProdGetter[_, _]]] = getterList.groupBy(getClasses)

  def getByClasses[Model, Field](modeClName: String, fieldClName: String): List[ProdGetter[Model, Field]] = byClassesMap.getOrElse((modeClName, fieldClName), Nil).map(_.asInstanceOf[ProdGetter[Model, Field]])

  lazy val modelById: Map[String, Int] = models.map(m => m.modelCl.getName -> m.modelId).toMap
  lazy val byCommonPrefix: Map[String, ProdGetter[_, _]] = (
    for {
      lens <- getterList
      model <- modelById.get(getClasses(lens)._1)
    } yield commonPrefix(model, getNames(lens)) -> lens
  ).toMap

  def getByCommonPrefix[Model, Field](commonPrefix: String): Option[ProdGetter[Model, Field]] =
    byCommonPrefix.get(commonPrefix).map(_.asInstanceOf[ProdGetter[Model, Field]])
}