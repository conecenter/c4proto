package examples

@main
object MyApp {
  println("HI+")
}

@schema
object MySchema {
  @Id(0x0003)
  trait ModelA {
    @Id(0x0004) def propA: Option[Int]
  }
  @Id(0x0001) trait ModelB {
    @Id(0x0005) def propB: Option[ModelA]
    @Id(0x0006) def propB0: Option[BigDecimal] @scale(2)
  }
}