package ee.cone.c4actor


trait LensRegistryAppTrait {
  def lensList: List[ProdLens[_, _]] = Nil

  def lensRegistry: LensRegistry
}

trait LensRegistryApp extends LensRegistryAppTrait {
  def lensRegistry = LensRegistryImpl(lensList)
}

trait LensRegistry {
  def get[C, I](lensName: String): ProdLens[C, I]
}

case class LensRegistryImpl(lensList: List[ProdLens[_, _]]) extends LensRegistry {
  private def getNameMetaAttr(prodLens: ProdLens[_, _]): NameMetaAttr = prodLens.metaList.find(_.isInstanceOf[NameMetaAttr])
    .getOrElse(throw new Exception("Lens w/o name!")).asInstanceOf[NameMetaAttr]

  lazy val lensMap: Map[String, ProdLens[_, _]] = lensList.map(lens ⇒ (getNameMetaAttr(lens).value, lens)).toMap

  def get[C, I](lensName: String): ProdLens[C, I] = lensMap.get(lensName).asInstanceOf[ProdLens[C, I]]
}