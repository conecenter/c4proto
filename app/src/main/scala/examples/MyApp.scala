package examples

@main
object MyApp {
  println("HI+")

  trait Lens[M,V] {
    def get(model: M): V
    def set(model: M, value: V): M
  }

  def lens[M,V](get: M⇒V): Lens[M,V]

  change(model.name, "Leader")

}


object BigDecimalImpl {
  def apply(scale: Int, bytes: okio.ByteString): BigDecimal =
    BigDecimal(new java.math.BigDecimal(new java.math.BigInteger(bytes.toByteArray), scale))
  def unapply(value: BigDecimal): Option[(Int,okio.ByteString)] = {
    val bytes = value.bigDecimal.unscaledValue.toByteArray
    Option((value.bigDecimal.scale, okio.ByteString.of(bytes,0,bytes.length)))
  }
}

@schema object DecimalSchema {
  trait SysBigDecimal {
    @Id(0x0001) def scale: Int
    @Id(0x0002) def bytes: okio.ByteString
  }
}




@schema object MySchema {
  import DecimalSchema._
  @Id(0x0003)
  trait ChildModel {
    @Id(0x0007) def name: String
    @Id(0x0004) def age: Option[BigDecimal] @scale(0)
  }
  @Id(0x0001) trait ParentModel {
    @Id(0x0005) def leader: Option[ChildModel]
    @Id(0x0006) def worker: List[ChildModel]
  }
}

