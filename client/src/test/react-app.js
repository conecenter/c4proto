
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"

function fail(data){ alert(data) }

const feedback = Feedback()
const vdom = VDomMix(feedback)
const receivers = [feedback.receivers, vdom.receivers, {fail}]
SSEConnection("http://localhost:8068/sse", receivers, 5)
