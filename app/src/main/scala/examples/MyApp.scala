package examples

@main
object MyApp {
  println("HI+")
}

@schema
object MySchema {
  trait ModelA {
    def propA: Int
  }
  @Id(0x0001) trait ModelB {
    def propB: Option[ModelA]
    def propB0: Option[BigDecimal] @scale(2)
  }
  trait ModelC
}