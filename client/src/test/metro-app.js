"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {VDomSender}  from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../main/canvas-manager"

import {CanvasBaseMix} from "../main/canvas-mix"
import * as CanvasExtra from "../extra/canvas-extra"
import CanvasExtraMix from "../extra/canvas-extra-mix"
import MetroUi       from "../extra/metro-ui"
import CustomUi      from "../extra/custom-ui"

function fail(data){ alert(data) }

const send = (url,options)=>fetch((window.feedbackUrlPrefix||"")+url, options)

const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const encode = value => btoa(unescape(encodeURIComponent(value)))
const sender = VDomSender(feedback,encode)

const log = v => console.log(v)
const getRootElement = () => document.body
const createElement = n => document.createElement(n)

const svgSrc = svg => "data:image/svg+xml;base64,"+window.btoa(svg)

//metroUi with hacks
const press = key => window.dispatchEvent(new KeyboardEvent("keydown",({key})))
const uglifyBody = style => {
    const node = document.querySelector("#content");
    if(node)
    while (node.hasChildNodes())
        node.removeChild(node.lastChild);
    document.body.style.margin="0rem";
    if(style)
        Object.assign(document.documentElement.style,style);
}
const metroUi = MetroUi({log,sender,setTimeout,clearTimeout,uglifyBody,press,svgSrc,addEventListener,removeEventListener});

//customUi with hacks
const toggleOverlay = on =>{
    if(on){
        const el=document.createElement("div");
        const style={
            position:"fixed",
            top:"0rem",
            left:"0rem",
            width:"100vw",
            height:"100vh",
            backgroundColor:"rgba(0,0,0,0.4)",
        };
        el.className="overlayMain";
        Object.assign(el.style,style);
        document.body.appendChild(el);
    }
    else{
        const el=document.querySelector(".overlayMain");
        if(el)	document.body.removeChild(el);
    }
}
const customMeasurer = () => window.CustomMeasurer ? [CustomMeasurer] : []
const customTerminal = () => window.CustomTerminal ? [CustomTerminal] : []
const customUi = CustomUi(metroUi,{log,ui:metroUi,customMeasurer,customTerminal,svgSrc,Image,setTimeout,clearTimeout,toggleOverlay});

//canvas
const util = Canvas.CanvasUtil()
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const mouseCanvasSystem = Canvas.MouseCanvasSystem(util,addEventListener)
const exchangeMix = canvas => [
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem,getComputedStyle),
    Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,feedback,getRootElement,getRootElement,createElement)
]
const canvasBaseMix = CanvasBaseMix(log,util)

const ddMix = canvas => CanvasExtra.DragAndDropCanvasSetup(canvas,log,setInterval,clearInterval,addEventListener)
const canvasMods = [canvasBaseMix,exchangeMix,CanvasExtraMix(log),ddMix]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods))

//transforms
const transforms = mergeAll([metroUi.transforms,customUi.transforms])

const vDom = VDomMix(console.log,sender,transforms,getRootElement,createElement)
const branches = Branches(log,mergeAll([vDom.branchHandlers,canvas.branchHandlers]))

const receiversList = [
    branches.receivers,
    feedback.receivers,
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
