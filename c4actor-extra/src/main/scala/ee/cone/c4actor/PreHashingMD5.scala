package ee.cone.c4actor

case class PreHashingMD5(idGenUtil: IdGenUtil) extends PreHashing {
  def wrap[T](value: T): PreHashed[T] = new PreHashedMD5(getProductHash(value), value, value.hashCode())

  def getProductHash[Model](model: Model): String = {
    val bytes = model match {
      case a: Int ⇒ getInt(a) :: Nil
      case b: Long ⇒ getLong(b) :: Nil
      case c: Boolean ⇒ getBoolean(c) :: Nil
      case d: okio.ByteString ⇒ getByteString(d) :: Nil
      case e: String ⇒ getString(e) :: Nil
      case f: PreHashedMD5[_] ⇒ getPreHashedMD5(f) :: Nil
      case i: List[_] ⇒ getList(i) :: Nil
      case g: Product ⇒ getProduct(g)
      case h ⇒ FailWith.apply(s"Unsupported type ${h.getClass} by PreHashingMD5")
    }
    idGenUtil.md5(bytes: _*)
  }

  def getInt: Int ⇒ Array[Byte] = number ⇒ getLong(number.toLong)

  def getLong: Long ⇒ Array[Byte] = idGenUtil.toBytes

  def getBoolean: Boolean ⇒ Array[Byte] = bool ⇒ Array.apply(if (bool) 1.toByte else 0.toByte)

  def getByteString: okio.ByteString ⇒ Array[Byte] = _.toByteArray

  def getString: String ⇒ Array[Byte] = idGenUtil.toBytes

  def getPreHashedMD5: PreHashedMD5[_] ⇒ Array[Byte] = preHashed ⇒ getString(preHashed.MD5Hash)

  def getProduct: Product ⇒ List[Array[Byte]] = product ⇒ {
    val classHash: Array[Byte] = getString(product.getClass.getName)
    val fields: List[Array[Byte]] =
      for {
        field ← product.productIterator.toList
      } yield {
        getString(getProductHash(field))
      }
    val hashedFields = idGenUtil.md5(fields: _*)
    val arityHash = getInt(product.productArity)
    classHash :: getString(hashedFields) :: arityHash :: Nil
  }

  def getList: List[_] ⇒ Array[Byte] = list ⇒
    idGenUtil.toBytes(idGenUtil.srcIdFromStrings((for {
      elem ← list
    } yield {
      getProductHash(elem)
    }): _*
    )
    )
}

final class PreHashedMD5[T](val MD5Hash: String, val value: T, override val hashCode: Int) extends PreHashed[T] {
  override def toString: String = s"PreHashedMD5(${value.toString})"

  override def equals(obj: scala.Any): Boolean =
    obj match {
      case a: PreHashedMD5[_] ⇒ a.MD5Hash == MD5Hash
      case _ ⇒ false
    }
}


