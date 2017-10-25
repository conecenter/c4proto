
import "babel-polyfill"
import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {VDomSender,pairOfInputAttributes,ctxToBranchPath}  from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../extra/canvas-manager"
import {ExampleAuth} from "../test/vdom-auth"
import {ExampleRequestState} from "../test/request-state"
import CanvasExtraMix from "../extra/canvas-extra-mix"
import {CanvasBaseMix,CanvasSimpleMix} from "../main/canvas-mix"


function fail(data){ alert(data) }

const send = fetch

const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback)
const exampleRequestState = ExampleRequestState(sender)

const log = v => console.log(v)
const getRootElement = () => document.body
const createElement = n => document.createElement(n)

const util = Canvas.CanvasUtil()
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const mouseCanvasSystem = Canvas.MouseCanvasSystem(util,addEventListener)
const exchangeMix = options => canvas => [
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem,getComputedStyle),
    Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,getRootElement,getRootElement,createElement)
]
const canvasBaseMix = CanvasBaseMix(log,util)

const canvasMods = [canvasBaseMix,exchangeMix,CanvasExtraMix(log)]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods), sender, ctxToBranchPath)

const exampleAuth = ExampleAuth(pairOfInputAttributes)
const transforms = mergeAll([exampleAuth.transforms, canvas.transforms])

const vDom = VDomMix(log,exampleRequestState,transforms,getRootElement,createElement)
const branches = Branches(log,vDom.branchHandlers)

const receiversList = [branches.receivers,feedback.receivers,{fail},exampleRequestState.receivers]
const createEventSource = () => new EventSource(location.protocol+"//"+location.host+"/sse")

const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, [connection.checkActivate,branches.checkActivate])
