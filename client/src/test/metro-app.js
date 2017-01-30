"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"
import MetroUi 		 from "../addon/metro-ui"
import CustomUi 	 from "../addon/custom-ui"
function fail(data){ alert(data) }

const feedback = Feedback()
const vdom = VDomMix(feedback)
const metroUi = MetroUi();
const customUi = CustomUi(metroUi);
vdom.transformBy({transforms:metroUi.transforms})
vdom.transformBy({transforms:customUi.transforms})
const receivers = [feedback.receivers, vdom.receivers,metroUi.receivers,customUi.receivers,{fail}]
if(parseInt(location.port)&&parseInt(location.port)!=80){
	SSEConnection(window.sseUrl||(location.protocol+"//"+location.hostname+":"+(parseInt(location.port)+1)+"/sse"), receivers, 5)
}
else
{
	SSEConnection(window.sseUrl||(location.protocol+"//"+location.host+"/sse"), receivers, 5)
}
