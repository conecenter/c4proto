package ee.cone.c4gate


import ee.cone.c4assemble.{Assemble,`The Assemble`}

trait ManagementApp extends `The Assemble` {

  override def `the List of Assemble`: List[Assemble] =
    new ManagementPostAssemble(getClass.getName) :: new PostConsumerAssemble(getClass.getName) ::
      super.`the List of Assemble`
}

/*
*
* Usage:
* curl $gate_addr_port/manage/$app_name -XPOST -HX-r-world-key:$worldKey -HX-r-selection:(all|keys|:$key)
* curl 127.0.0.1:8067/manage/ee.cone.c4gate.TestPasswordApp -XPOST -HX-r-world-key:SrcId,TxTransform -HX-r-selection:all
*
* */