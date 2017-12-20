package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, Config, SyncTxFactory, SyncTxFactoryImpl}
import ee.cone.c4assemble.Assemble

trait ManagementApp extends AssemblesApp with ActorAccessApp with PrometheusApp {
  def config: Config
  lazy val syncTxFactory: SyncTxFactory = new SyncTxFactoryImpl

  override def assembles: List[Assemble] =
    new ManagementPostAssemble(getClass.getName) :: new PostConsumerAssembles(getClass.getName, syncTxFactory)() ::
      super.assembles
}

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -HX-r-world-key:$worldKey -HX-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -HX-r-world-key:SrcId,TxTransform -HX-r-selection:all
*
* */