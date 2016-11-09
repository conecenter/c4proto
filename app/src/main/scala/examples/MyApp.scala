package examples

@main
object MyApp {
  println("HI+")
  import MySchema._
  val leader0 = Person("leader0", Some(40))
  val worker0 = Person("worker0", Some(30))
  val worker1 = Person("worker1", Some(20))
  val group0 = Group(Some(leader0), List(worker0,worker1))
  val findAdapter = new FindAdapter(MySchema)()
  val adapter = findAdapter(group0)
  val bytes = adapter.encode(group0)
  println(bytes.toList)
  //println(new String(bytes))
  val group1 = adapter.decode(bytes)
  println(group0,group1,group0==group1)
/*
  com.squareup.wire.ProtoAdapter
  classOf[String].getName

  trait Lens[M,V] {
    def get(model: M): V
    def set(model: M, value: V): M
  }

  def lens[M,V](get: M⇒V): Lens[M,V]

  change(model.name, "Leader")
*/
}

import com.squareup.wire.ProtoAdapter

trait Schema {
  def adapters: List[ProtoAdapter[_<:Object] with ProtoAdapterWithId] = ???
}

object BigDecimalFactory {
  def apply(scale: Int, bytes: okio.ByteString): BigDecimal =
    BigDecimal(new java.math.BigDecimal(new java.math.BigInteger(bytes.toByteArray), scale))
  def unapply(value: BigDecimal): Option[(Int,okio.ByteString)] = {
    val bytes = value.bigDecimal.unscaledValue.toByteArray
    Option((value.bigDecimal.scale, okio.ByteString.of(bytes,0,bytes.length)))
  }
}

@schema object DecimalSchema extends Schema {
  case class SysBigDecimal(@Id(0x0001) scale: Int, @Id(0x0002) bytes: okio.ByteString)
}

class FindAdapter(schemaList: Schema*)(
  val byName: Map[String,ProtoAdapter[_<:Object]] =
    schemaList.flatMap(_.adapters).map(a ⇒ a.className → a).toMap
) {
  def apply[M](model: M) =
    byName(model.getClass.getName).asInstanceOf[ProtoAdapter[M]]
}

////

@schema object MySchema extends Schema {
  import DecimalSchema._
  @Id(0x0003) case class Person(@Id(0x0007) name: String, @Id(0x0004) age: Option[BigDecimal] @scale(0))
  @Id(0x0001) case class Group(@Id(0x0005) leader: Option[Person], @Id(0x0006) worker: List[Person])
}

