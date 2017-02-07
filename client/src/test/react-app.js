
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import CanvasMix     from "../main/canvas-mix"
import * as Canvas   from "../main/canvas"

function fail(data){ alert(data) }

const feedback = Feedback(localStorage,sessionStorage,document.location,fetch)
window.onhashchange = () => feedback.pong()
const transforms = {}
const encode = value => btoa(unescape(encodeURIComponent(value)))
const sender = VDomSender(feedback,encode)
const getRootElement = () => document.body
const createElement = n => document.createElement(n)
const vDom = VDomMix(sender,transforms,getRootElement,createElement)

const util = Canvas.CanvasUtil()
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const mouseCanvasSystem = Canvas.MouseCanvasSystem(util,addEventListener)
const canvas = CanvasMix(util,canvas=>[
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem,getComputedStyle),
    Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,feedback,getRootElement,getRootElement,createElement),
    Canvas.TiledCanvasSetup(canvas),
    Canvas.DragViewPositionCanvasSetup(canvas),
    Canvas.NoOverlayCanvasSetup
])

const branches = Branches(mergeAll([vDom.branchHandlers,canvas.branchHandlers]))
const receiversList = [].concat([branches.receivers,feedback.receivers,{fail}])
const createEventSource = () => new EventSource("http://localhost:8068/sse")
const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, [connection.checkActivate,branches.checkActivate])
