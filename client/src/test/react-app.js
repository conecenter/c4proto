
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"
import Branches      from "../main/branches"

function fail(data){ alert(data) }

const feedback = Feedback()
const vdom = VDomMix(feedback,{})
const branches = Branches(vdom.branchHandlers)
const receiversList = [].concat([branches.receivers,vdom.receivers,feedback.receivers,{fail}])
SSEConnection("http://localhost:8068/sse", receiversList, 5)
