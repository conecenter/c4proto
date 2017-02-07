"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {VDomSender}  from "../main/vdom-util"
import MetroUi       from "../extra/metro-ui"
import CustomUi      from "../extra/custom-ui"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"

function fail(data){ alert(data) }

const send = (url,options)=>fetch((window.feedbackUrlPrefix||"")+url, options)
const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback,encode)
const metroUi = MetroUi();
const customUi = CustomUi(metroUi);
const transforms = mergeAll([metroUi.transforms,customUi.transforms])
const encode = value => btoa(unescape(encodeURIComponent(value)))
const getRootElement = () => document.body
const createElement = n => document.createElement(n)
const vdom = VDomMix(console.log,sender,transforms,getRootElement,createElement)
const branches = Branches(log,vdom.branchHandlers)
const receiversList = [
    branches.receivers,
    feedback.receivers,
    metroUi.receivers,
    customUi.receivers,
    {fail}
]
const composeUrl = () => {
    const port = parseInt(location.port)
    const hostPort = port && port != 80 ? location.hostname+":"+(port+1) : location.host
    return location.protocol+"//"+hostPort+"/sse"
}
const createEventSource = () => new EventSource(window.sseUrl||composeUrl())
const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, [connection.checkActivate,branches.checkActivate])