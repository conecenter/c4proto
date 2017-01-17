"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"
import MetroUi 		 from "../addon/metro-ui"	
function fail(data){ alert(data) }

const feedback = Feedback()
const vdom = VDomMix(feedback)
const metroUi = MetroUi();
vdom.transformBy({transforms:metroUi.transforms})
const receivers = [feedback.receivers, vdom.receivers,metroUi.receivers,{fail}]
SSEConnection(window.sseUrl||"http://localhost:8068/sse", receivers, 5)
