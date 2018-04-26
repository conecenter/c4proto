package ee.cone.c4gate.dep

import ee.cone.c4actor.dep.DepMainHashSearchMixApp
import ee.cone.c4gate.dep.request.{CurrentTimeHandlerApp, FilterListRequestHandlerApp}

trait DepFilterListMainApp
extends DepMainHashSearchMixApp
with FilterListRequestHandlerApp
with SessionAttrAskMix
with CurrentTimeAskMix
with CurrentTimeHandlerApp
