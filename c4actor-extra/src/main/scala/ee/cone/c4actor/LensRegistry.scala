package ee.cone.c4actor


trait ProdLensesApp {
  def lensList: List[ProdLens[_, _]] = Nil
}

trait LensRegistryApp {
  def lensRegistry: LensRegistryApi
}

trait LensRegistryMix extends ProdLensesApp {
  def lensRegistry = {
    lensIntegrityCheck
    LensRegistryImpl(lensList)
  }

  private def lensIntegrityCheck = {
    val errors = lensList.groupBy(prodLens ⇒ prodLens.metaList.collect { case a: NameMetaAttr ⇒ a }
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
}

case class LensRegistryImpl(lensList: List[ProdLens[_, _]]) extends LensRegistryApi {
  private def getNames(prodLens: ProdLens[_, _]): List[String] = prodLens.metaList.collect { case a: NameMetaAttr ⇒ a }
    .map(_.value) match {
    case Nil ⇒ FailWith.apply(s"Lens without name in LensRegistryImpl: $prodLens")
    case a ⇒ a
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
}