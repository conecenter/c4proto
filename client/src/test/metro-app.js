"use strict";

import "babel-polyfill"
import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {VDomSender}  from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../main/canvas-manager"
import ScannerProxy  from "../extra/scanner-proxy"

import {CanvasBaseMix} from "../main/canvas-mix"
import * as CanvasExtra from "../extra/canvas-extra"
import CanvasExtraMix from "../extra/canvas-extra-mix"
import MetroUi       from "../extra/metro-ui"
import CustomUi      from "../extra/custom-ui"
import CryptoElements from "../extra/crypto-elements"

const send = (url,options)=>fetch((window.feedbackUrlPrefix||"")+url, options)

const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback)

const log = v => console.log("log",v)
const getRootElement = () => document.body
const createElement = n => document.createElement(n)

const svgSrc = svg => "data:image/svg+xml;base64,"+window.btoa(svg)

//metroUi with hacks
const press = key => window.dispatchEvent(new KeyboardEvent("keydown",({key})))
const getComputedStyle = n => window.getComputedStyle(n);
const getPageYOffset = ()=> window.pageYOffset;
const fileReader = ()=> (new window.FileReader());
const getWindowRect = () => ({top:0,left:0,bottom:window.innerHeight,right:window.innerWidth,height:window.innerHeight,width:window.innerWidth});
const bodyManager = (()=>{
	const add = (node) => document.body.appendChild(node)
	const addFirst = (node) => document.body.insertBefore(node,document.body.firstChild)
	const remove = (node) => document.body.removeChild(node)
	const createElement = (type) => document.createElement(type)
	const body = () => document.body
	return {add,addFirst,remove,createElement,body}
})()

const metroUi = MetroUi({log,sender,setTimeout,clearTimeout,press,svgSrc,addEventListener,removeEventListener,getComputedStyle,fileReader,getPageYOffset,bodyManager,getWindowRect});
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
			zIndex:"6666",
            backgroundColor:"rgba(0,0,0,0.4)",
        };
        el.className="overlayMain";
        Object.assign(el.style,style);
        document.body.appendChild(el);
    }
    else{
        const el=document.querySelector(".overlayMain");
        if(el) document.body.removeChild(el);
    }
}
const customMeasurer = () => window.CustomMeasurer ? [CustomMeasurer] : []
const customTerminal = () => window.CustomTerminal ? [CustomTerminal] : []
const getBattery = typeof navigator.getBattery =="function"?(callback) => navigator.getBattery().then(callback):null
const Scanner = window.Scanner
const innerHeight = () => window.innerHeight
const scrollBy = (x,y) => window.scrollBy(x,y)
const scannerProxy = ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,document,scrollBy})
window.ScannerProxy = scannerProxy
const customUi = CustomUi({log,ui:metroUi,customMeasurer,customTerminal,svgSrc,Image,setTimeout,clearTimeout,toggleOverlay,getBattery,scannerProxy});

const activeElement=()=>document.activeElement; //todo: remove

//canvas
const util = Canvas.CanvasUtil()
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const mouseCanvasSystem = Canvas.MouseCanvasSystem(util,addEventListener)
const exchangeMix = options => canvas => [
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem,getComputedStyle),
    Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,feedback,getRootElement,getRootElement,createElement,activeElement)
]
const canvasBaseMix = CanvasBaseMix(log,util)

const ddMix = options => canvas => CanvasExtra.DragAndDropCanvasSetup(canvas,log,setInterval,clearInterval,addEventListener)
const canvasMods = [canvasBaseMix,exchangeMix,CanvasExtraMix(log),ddMix]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods))
const parentWindow = ()=> parent
const cryptoElements = CryptoElements({log,feedback,ui:metroUi,hwcrypto:window.hwcrypto,atob,parentWindow});
//transforms
const transforms = mergeAll([metroUi.transforms,customUi.transforms,cryptoElements.transforms])

const vDom = VDomMix(console.log,sender,transforms,getRootElement,createElement)
const branches = Branches(log,mergeAll([vDom.branchHandlers,canvas.branchHandlers]))

const receiversList = [
    branches.receivers,
    feedback.receivers,
	metroUi.receivers,
    customUi.receivers,
	cryptoElements.receivers	  
]
const composeUrl = () => {
    const port = parseInt(location.port)
    const hostPort = port && port != 80 ? location.hostname+":"+(port+1) : location.host
    return location.protocol+"//"+hostPort+"/sse"
}
const createEventSource = () => new EventSource(window.sseUrl||composeUrl())

const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, [connection.checkActivate,branches.checkActivate,metroUi.checkActivate])
