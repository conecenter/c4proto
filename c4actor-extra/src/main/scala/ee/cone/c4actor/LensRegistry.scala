package ee.cone.c4actor


trait ProdLensesApp {
  def lensList: List[ProdLens[_, _]] = Nil
}

trait LensRegistryApp {
  def lensRegistry: LensRegistryApi
}

trait LensRegistryMix extends ProdLensesApp {
  def lensRegistry = LensRegistryImpl(lensList)
}

trait LensRegistryApi {
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
}