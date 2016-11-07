package examples

@main
object MyApp {
  println("HI+")
}

@schema
object MySchema {
  @Id(0x0003)
  trait ModelA {
    def propA: Option[Int]
  }
  @Id(0x0001) trait ModelB {
    def propB: Option[ModelA]
    def propB0: Option[BigDecimal] @scale(2)
  }
}