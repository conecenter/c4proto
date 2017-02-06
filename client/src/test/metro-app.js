"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"
import MetroUi 		 from "../addon/metro-ui"
import CustomUi 	 from "../addon/custom-ui"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"

function fail(data){ alert(data) }

const feedback = Feedback(localStorage,sessionStorage,()=>document.location)
const metroUi = MetroUi();
const customUi = CustomUi(metroUi);
const vdom = VDomMix(feedback,mergeAll([metroUi.transforms,customUi.transforms]),document.body)
const branches = Branches(vdom.branchHandlers)
const receiversList = [].concat([branches.receivers,feedback.receivers,metroUi.receivers,customUi.receivers,{fail}])

if(parseInt(location.port)&&parseInt(location.port)!=80){
	SSEConnection(()=>new EventSource(window.sseUrl||(location.protocol+"//"+location.hostname+":"+(parseInt(location.port)+1)+"/sse")), receiversList, 5)
}
else
{
	SSEConnection(()=>new EventSource(window.sseUrl||(location.protocol+"//"+location.host+"/sse")), receiversList, 5)
}
branches.start(requestAnimationFrame)