package ee.cone.c4gate


trait ManagementApp extends `The ManagementPostAssemble`
  with `The PostConsumerAssemble` with `The PostConsumerAssembles`
  with `The ActorAccessAssemble` with `The ActorAccessProtocol`
  with `The PrometheusAssemble`

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -HX-r-world-key:$worldKey -HX-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -HX-r-world-key:SrcId,TxTransform -HX-r-selection:all
*
* */