
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import CanvasMix     from "../main/canvas-mix"
import * as Canvas   from "../main/canvas"

function fail(data){ alert(data) }

const feedback = Feedback(localStorage,sessionStorage,()=>document.location)
const vdom = VDomMix(feedback,{},document.body)

const createElement = n => document.createElement(n)
const appendToRoot = e => document.body.appendChild(e)
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const canvas = CanvasMix(canvas=>[
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,feedback,()=>document.body,createElement,appendToRoot),
    Canvas.TiledCanvasSetup(canvas),
    Canvas.DragViewPositionCanvasSetup(canvas),
    Canvas.NoOverlayCanvasSetup
])

const branches = Branches(mergeAll([vdom.branchHandlers,canvas.branchHandlers]))
const receiversList = [].concat([branches.receivers,feedback.receivers,{fail}])
SSEConnection(()=>new EventSource("http://localhost:8068/sse"), receiversList, 5)
branches.start(requestAnimationFrame)
