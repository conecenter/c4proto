
import "babel-polyfill"
import SSEConnection from "../main/sse-connection"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {chain,mergeAll}    from "../main/util"
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

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods))

const transforms = {}

const vDom = VDomMix({log,encode,transforms,getRootElement,createElement})
const branches = Branches(log,mergeAll([vDom.branchHandlers,canvas.branchHandlers]))

const receiversList = [branches.receivers,{fail}]
const createEventSource = () => new EventSource("http://localhost:8068/sse")

const reconnectTimeout = 5000
const checkActivate = chain([vDom.checkActivate,canvas.checkActivate])
const connection = SSEConnection({createEventSource,receiversList,checkActivate,reconnectTimeout,localStorage,sessionStorage,location,send})
activate(requestAnimationFrame, connection.checkActivate)
