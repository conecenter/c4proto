package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.request.ByPKRequestHandlerApp
import ee.cone.c4actor.hashsearch.base.HashSearchAssembleApp
import ee.cone.c4actor.hashsearch.index.HashSearchStaticLeafFactoryMix
import ee.cone.c4actor.utils.GeneralizedOrigFactoryMix

trait DepMainMixApp
  extends DepAssembleApp
    with RequestHandlerRegistryImplApp
    with ByPKRequestHandlerApp
    with CommonRequestUtilityMix

trait DepMainHashSearchMixApp
  extends DepMainMixApp
    with HashSearchAssembleApp
    with HashSearchStaticLeafFactoryMix
    with GeneralizedOrigFactoryMix

