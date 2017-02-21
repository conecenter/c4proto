
import "babel-polyfill"
import SSEConnection from "../main/sse-connection"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {mergeAll,chain,addSend}    from "../main/util"
import Branches      from "../main/branches"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../main/canvas-manager"

import {CanvasBaseMix,CanvasSimpleMix} from "../main/canvas-mix"


function fail(data){ alert(data) }

const send = fetch

const encode = value => btoa(unescape(encodeURIComponent(value)))


const log = v => console.log(v)
const getRootElement = () => document.body
const createElement = n => document.createElement(n)

const util = Canvas.CanvasUtil()
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const mouseCanvasSystem = Canvas.MouseCanvasSystem(util,addEventListener)
const exchangeMix = canvas => [
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem,getComputedStyle),
    Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,getRootElement,getRootElement,createElement)
]
const canvasBaseMix = CanvasBaseMix(log,util)

const canvasMods = [canvasBaseMix,exchangeMix,CanvasSimpleMix()]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods),transformNested,chain)

const transforms = {}

const vDom = VDomMix({log,encode,transforms,getRootElement,createElement})
const branches = Branches(log,mergeAll([vDom.branchHandlers,canvas.branchHandlers]),transformNested)

const receiversList = [branches.receivers,{fail}]
const createEventSource = () => new EventSource("http://localhost:8068/sse")

const reconnectTimeout = 5000
const connection = SSEConnection({createEventSource,receiversList,reconnectTimeout,localStorage,sessionStorage,location,send,addSend})
activate(requestAnimationFrame, [connection.checkActivate,branches.checkActivate],chain)
