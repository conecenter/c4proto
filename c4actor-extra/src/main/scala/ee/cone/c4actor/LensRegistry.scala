package ee.cone.c4actor

import ee.cone.c4actor.hashsearch.index.dynamic.{DynamicIndexModelsApp, HashSearchIdGeneration, ProductWithId}


trait ProdLensesApp {
  def lensList: List[ProdLens[_, _]] = Nil
}

trait LensRegistryApp {
  def lensRegistry: LensRegistryApi
}

trait LensRegistryMix extends ProdLensesApp with DynamicIndexModelsApp{
  def lensRegistry = {
    lensIntegrityCheck
    LensRegistryImpl(lensList.distinct, dynIndexModels.distinct)
  }

  private def lensIntegrityCheck = {
    val errors = lensList.distinct.groupBy(prodLens ⇒ prodLens.metaList.collect { case a: NameMetaAttr ⇒ a }
      .map(_.value) match {
      case Nil ⇒ FailWith.apply(s"Lens without name in LensRegistryImpl: $prodLens")
      case a ⇒ a
    }
    ).filter(_._2.size > 1)
    if (errors.nonEmpty)
      FailWith.apply(s"Non unique lens name: $errors")
  }
}

trait LensRegistryApi {
  def getOpt[C, I](lensName: List[String]): Option[ProdLens[C, I]]

  def get[C, I](lensName: List[String]): ProdLens[C, I]

  def get[Model](lensName: List[String], modelCl: Class[Model]): ProdLens[Model, _]

  def get[Model, Field](lensName: List[String], modelCl: Class[Model], fieldCl: Class[Field]): ProdLens[Model, Field]

  def getByClasses[Model, Field](modeClName: String, fieldClName: String): List[ProdLens[Model, Field]]

  def getByCommonPrefix[Model, Field](commonPrefix: String): Option[ProdLens[Model, Field]]
}

case class ClassesAttr(modelClName: String, fieldClName: String) extends AbstractMetaAttr

case class LensRegistryImpl(lensList: List[ProdLens[_, _]], models: List[ProductWithId[_ <: Product]]) extends LensRegistryApi with HashSearchIdGeneration {
  private def getNames(prodLens: ProdLens[_, _]): List[String] = prodLens.metaList.collect { case a: NameMetaAttr ⇒ a }
    .map(_.value) match {
    case Nil ⇒ FailWith.apply(s"Lens without name in LensRegistryImpl: $prodLens, supply NameMetaAttr")
    case a ⇒ a
  }

  private def getClasses(prodLens: ProdLens[_, _]): (String, String) = prodLens.metaList.collectFirst { case a: ClassesAttr ⇒ a } match {
    case None ⇒ FailWith.apply(s"Lens without modelName in LensRegistryImpl: $prodLens, supply ClassAttr")
    case Some(str) ⇒ (str.modelClName, str.fieldClName)
  }

  lazy val lensMap: Map[List[String], ProdLens[_, _]] = lensList.map(lens ⇒ (getNames(lens), lens)).toMap

  def get[C, I](lensName: List[String]): ProdLens[C, I] =
    lensMap(lensName).asInstanceOf[ProdLens[C, I]]

  def get[Model](lensName: List[String], modelCl: Class[Model]): ProdLens[Model, _] =
    lensMap(lensName).asInstanceOf[ProdLens[Model, _]]

  def get[Model, Field](lensName: List[String], modelCl: Class[Model], fieldCl: Class[Field]): ProdLens[Model, Field] =
    lensMap(lensName).asInstanceOf[ProdLens[Model, Field]]

  def getOpt[C, I](lensName: List[String]): Option[ProdLens[C, I]] =
    lensMap.get(lensName).map(_.asInstanceOf[ProdLens[C, I]])

  lazy val byClassesMap: Map[(String, String), List[ProdLens[_, _]]] = lensList.groupBy(getClasses)

  def getByClasses[Model, Field](modeClName: String, fieldClName: String): List[ProdLens[Model, Field]] = byClassesMap.getOrElse((modeClName, fieldClName), Nil).map(_.asInstanceOf[ProdLens[Model, Field]])

  lazy val modelById: Map[String, Int] = models.map(m ⇒ m.modelCl.getName → m.modelId).toMap
  lazy val byCommonPrefix: Map[String, ProdLens[_, _]] = lensList.map(lens ⇒ commonPrefix(modelById(getClasses(lens)._1), getNames(lens)) → lens).toMap

  def getByCommonPrefix[Model, Field](commonPrefix: String): Option[ProdLens[Model, Field]] =
    byCommonPrefix.get(commonPrefix).map(_.asInstanceOf[ProdLens[Model, Field]])
}