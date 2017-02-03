
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import VDomMix       from "../main/vdom-mix"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import CanvasMix     from "../main/canvas-mix"
import * as Canvas   from "../main/canvas"

function fail(data){ alert(data) }

const feedback = Feedback()
const vdom = VDomMix(feedback,{})
const canvas = CanvasMix(canvas=>[
    Canvas.ExchangeCanvasSetup(canvas,feedback),
    Canvas.TiledCanvasSetup(canvas),
    Canvas.DragViewPositionCanvasSetup(canvas),
    Canvas.NoOverlayCanvasSetup
])
const branches = Branches(mergeAll([vdom.branchHandlers,canvas.branchHandlers]))
const receiversList = [].concat([branches.receivers,feedback.receivers,{fail}])
SSEConnection("http://localhost:8068/sse", receiversList, 5)
branches.start()
