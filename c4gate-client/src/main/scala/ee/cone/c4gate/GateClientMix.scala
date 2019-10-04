package ee.cone.c4gate

import ee.cone.c4actor.GzipFullCompressor
import ee.cone.c4proto.c4

trait FilterPredicateBuilderAppBase

trait ActorAccessAppBase
trait ManagementAppBase extends ActorAccessApp with PrometheusApp
trait PrometheusAppBase extends DefPublishFullCompressorApp

trait AvailabilityAppBase

trait DefPublishFullCompressorAppBase
trait PublishingCompAppBase extends HttpProtocolApp with DefPublishFullCompressorApp
@c4("DefPublishFullCompressorApp") class DefPublishFullCompressor extends PublishFullCompressor(GzipFullCompressor())

trait SessionAttrAppBase extends SessionDataProtocolApp
trait SessionDataProtocolAppBase

trait AlienProtocolAppBase
trait AuthProtocolAppBase
trait HttpProtocolAppBase


// def availabilityDefaultUpdatePeriod: Long = 3000
// def availabilityDefaultTimeout: Long = 3000

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -Hx-r-world-key:$worldKey -Hx-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -Hx-r-world-key:SrcId,TxTransform -Hx-r-selection:all
*
* */