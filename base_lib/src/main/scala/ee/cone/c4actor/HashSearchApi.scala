package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assemble

class HashSearchFactoryHolder(val value: HashSearch.Factory)
object HashSearch {

  case class Request[Model <: Product](requestId: SrcId, condition: Condition[Model])

  case class Response[Model <: Product](srcId: SrcId, request: Request[Model], linesHashed: PreHashed[Option[List[Model]]]) extends {
    def lines: List[Model] = linesHashed.value.get
  }

  trait Factory {
    def index[Model <: Product](model: Class[Model]): IndexBuilder[Model]

    def request[Model <: Product](condition: Condition[Model]): Request[Model]
  }

  trait IndexBuilder[Model <: Product] {
    def add[By <: Product, Field](lens: ProdLens[Model, Field], by: By)(
      implicit ranger: Ranger[By, Field]
    ): IndexBuilder[Model]

    def assemble: Assemble
  }

}
