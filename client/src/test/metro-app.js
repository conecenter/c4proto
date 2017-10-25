"use strict";

import "babel-polyfill"
import "whatwg-fetch"
import "eventsource-polyfill"
import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import VDomMix       from "../main/vdom-mix"
import {VDomSender,ctxToBranchPath}  from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import Branches      from "../main/branches"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../extra/canvas-manager"
import ScannerProxy  from "../extra/scanner-proxy"

import {CanvasBaseMix} from "../main/canvas-mix"
import * as CanvasExtra from "../extra/canvas-extra"
import CanvasExtraMix from "../extra/canvas-extra-mix"
import MetroUi       from "../extra/metro-ui"
import CustomUi      from "../extra/custom-ui"
import CryptoElements from "../extra/crypto-elements"
import FocusModule		from "../extra/focus-module"
import DragDropModule from "../extra/dragdrop-module"
import OverlayManager from "../extra/overlay-manager"
import RequestState from "../extra/request-state"
import WinWifi from "../extra/win-wifi-status"

import UpdateManager from "../extra/update-manager"

const send = (url,options)=>fetch((window.feedbackUrlPrefix||"")+url, options)

const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback)
const log = v => console.log("log",v)
const requestState = sender//RequestState(sender,log)
const getRootElement = () => document.body
const createElement = n => document.createElement(n)
const svgSrc = svg => "data:image/svg+xml;base64,"+window.btoa(svg)
//metroUi with hacks
const press = key => window.dispatchEvent(new KeyboardEvent("keydown",({key})))
const fileReader = ()=> (new window.FileReader());

const windowManager = (()=>{
	const getWindowRect = () => ({top:0,left:0,bottom:window.innerHeight,right:window.innerWidth,height:window.innerHeight,width:window.innerWidth})
	const getPageYOffset = ()=> window.pageYOffset
	const getComputedStyle = n => window.getComputedStyle(n)
	const screenRefresh = () => location.reload()
	return {getWindowRect,getPageYOffset,getComputedStyle,addEventListener,removeEventListener,setTimeout,clearTimeout,screenRefresh}
})()
const documentManager = (()=>{
	const add = (node) => document.body.appendChild(node)
	const addFirst = (node) => document.body.insertBefore(node,document.body.firstChild)
	const remove = (node) => document.body.removeChild(node)
	const createElement = (type) => document.createElement(type)
	const body = () => document.body
	const execCopy = () => document.execCommand('copy')
	const activeElement = () =>document.activeElement
	const nodeFromPoint = (x,y)=>document.elementFromPoint(x,y)
	return {add,addFirst,remove,createElement,body,execCopy,activeElement,document,nodeFromPoint}
})()
const eventManager = (()=>{
	const create = (type,params) => {
		switch(type){
			case "keydown": return (new KeyboardEvent(type,params))
			case "click": return (new MouseEvent(type,params))
			default: return (new CustomEvent(type,params))
		}
	}
	return {create}
})()

const miscReact = (()=>{
	const isReactRoot = function(el){
		if(el.dataset["reactroot"]=="") return true
		return false
	}
	const getReactRoot = function(el){
		if(!el) return documentManager.body().querySelector("[data-reactroot]")		
		if(isReactRoot(el) || !el.parentNode) return el
		const parentEl = el.parentNode
		return getReactRoot(parentEl)
	}	
	return {isReactRoot,getReactRoot}
})()
const overlayManager = OverlayManager({log,documentManager,windowManager})
const focusModule = FocusModule({log,documentManager,eventManager,windowManager,miscReact})
const dragDropModule = DragDropModule({log,documentManager,windowManager})
const metroUi = MetroUi({log,sender:requestState,press,svgSrc,fileReader,documentManager,focusModule,eventManager,dragDropModule,windowManager,miscReact});
//customUi with hacks
const customMeasurer = () => window.CustomMeasurer ? [CustomMeasurer] : []
const customTerminal = () => window.CustomTerminal ? [CustomTerminal] : []
const getBattery = typeof navigator.getBattery =="function"?(callback) => navigator.getBattery().then(callback):null
const Scanner = window.Scanner
const innerHeight = () => window.innerHeight
const scrollBy = (x,y) => window.scrollBy(x,y)
const scannerProxy = ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,documentManager,scrollBy,eventManager})
window.ScannerProxy = scannerProxy
const winWifi = WinWifi(log,window.require,window.process,setInterval)
window.winWifi = winWifi
const customUi = CustomUi({log,ui:metroUi,requestState,customMeasurer,customTerminal,svgSrc,Image,overlayManager,getBattery,scannerProxy,windowManager,winWifi});
const updateManager = UpdateManager(log,window,metroUi)
const activeElement=()=>document.activeElement; //todo: remove

//canvas
const util = Canvas.CanvasUtil()
const resizeCanvasSystem = Canvas.ResizeCanvasSystem(util,createElement)
const mouseCanvasSystem = Canvas.MouseCanvasSystem(util,addEventListener)
const exchangeMix = options => canvas => [
    Canvas.ResizeCanvasSetup(canvas,resizeCanvasSystem,getComputedStyle),
    Canvas.MouseCanvasSetup(canvas,mouseCanvasSystem),
    Canvas.ExchangeCanvasSetup(canvas,getRootElement,getRootElement,createElement,activeElement)
]
const canvasBaseMix = CanvasBaseMix(log,util)

const ddMix = options => canvas => CanvasExtra.DragAndDropCanvasSetup(canvas,log,setInterval,clearInterval,addEventListener)
const canvasMods = [canvasBaseMix,exchangeMix,CanvasExtraMix(log),ddMix]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods), sender, ctxToBranchPath)
const parentWindow = ()=> parent
const cryptoElements = CryptoElements({log,feedback,ui:metroUi,hwcrypto:window.hwcrypto,atob,parentWindow});
//transforms
const transforms = mergeAll([metroUi.transforms,customUi.transforms,cryptoElements.transforms,updateManager.transforms])

const vDom = VDomMix(console.log,requestState,transforms,getRootElement,createElement)

const branches = Branches(log,mergeAll([vDom.branchHandlers,canvas.branchHandlers]))

const receiversList = [
    branches.receivers,
    feedback.receivers,
	metroUi.receivers,
    customUi.receivers,
	cryptoElements.receivers,
	focusModule.receivers/*,
	requestState.receivers*/
]
const composeUrl = () => {
    const port = parseInt(location.port)
    const hostPort = port && port != 80 ? location.hostname+":"+(port+1) : location.host
    return location.protocol+"//"+hostPort+"/sse"
}
const createEventSource = () => new EventSource(window.sseUrl||composeUrl())

const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(window.requestAnimationFrame || (cb=>setTimeout(cb,16)), [
    connection.checkActivate,
    branches.checkActivate,
    metroUi.checkActivate,
    focusModule.checkActivate,
    dragDropModule.checkActivate,
	updateManager.checkActivate
])
