package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assemble

object HashSearch {
  case class Request[Model<:Product](requestId: SrcId, condition: Condition[Model])
  case class Response[Model<:Product](srcId: SrcId, request: Request[Model], lines: List[Model])
  trait Factory {
    def index[Model<:Product](model: Class[Model]): IndexBuilder[Model]
    def request[Model<:Product](condition: Condition[Model]): Request[Model]
  }
  trait IndexBuilder[Model<:Product] {
    def add[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
      implicit ranger: Ranger[By,Field]
    ): IndexBuilder[Model]
    def assemble: Assemble
  }

  trait StaticFactory {
    def index[Model<:Product](model: Class[Model]): StaticIndexBuilder[Model]
    def request[Model<:Product](condition: Condition[Model]): Request[Model]
  }
  trait StaticIndexBuilder[Model<:Product] {
    def add[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
      implicit ranger: Ranger[By,Field]
    ): IndexBuilder[Model]
    def assemble: List[Assemble]
  }
}
