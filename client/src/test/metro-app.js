"use strict";

import "babel-polyfill"
import "whatwg-fetch"
import "eventsource-polyfill"
import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import withState     from "../main/active-state"
import {VDomCore,VDomAttributes} from "../main/vdom-core"
import {VDomSender}  from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../main/canvas-manager"
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
import React from 'react'
import SwitchHost from "../extra/switchhost-module"
import UpdateManager from "../extra/update-manager"
import VirtualKeyboard from "../extra/virtual-keyboard"
import autoBind from 'react-autobind'
const send = (url,options)=>fetch((window.feedbackUrlPrefix||"")+url, options)
const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback)
const log = v => { if(window.console) console.log("log",v)}
const requestState = sender//RequestState(sender,log)
const getRootElement = () => document.body
const svgSrc = svg => "data:image/svg+xml;base64,"+window.btoa(svg)

class StatefulComponent extends React.Component {
	constructor(props) {
	  super(props);
	  this.state = this.getInitialState?this.getInitialState():{}
	  autoBind(this)
	}
}
class StatefulPureComponent extends React.PureComponent {
    constructor(props) {
      super(props);
      this.state = this.getInitialState?this.getInitialState():{}
      autoBind(this)
    }
}
const windowManager = (()=>{
	const getWindowRect = () => ({top:0,left:0,bottom:window.innerHeight,right:window.innerWidth,height:window.innerHeight,width:window.innerWidth})
	const getPageYOffset = ()=> window.pageYOffset
	const getComputedStyle = n => window.getComputedStyle(n)
	const screenRefresh = () => location.reload()
	const location = () => window.location
	return {getWindowRect,setInterval,clearInterval,getPageYOffset,getComputedStyle,addEventListener,removeEventListener,setTimeout,clearTimeout,screenRefresh,location, urlPrefix:window.feedbackUrlPrefix,location}
})()
const documentManager = (()=>{
	const add = (node) => document.body.appendChild(node)
	const addFirst = (node) => document.body.insertBefore(node,document.body.firstChild)
	const remove = (node) => node.parentElement.removeChild(node)
	const createElement = (type) => document.createElement(type)
	const elementsFromPoint = (x,y)=> document.elementsFromPoint(x,y)
	const body = () => document.body
	const execCopy = () => document.execCommand('copy')
	const activeElement = () =>document.activeElement
	const nodeFromPoint = (x,y)=>document.elementFromPoint(x,y)
	return {add,addFirst,remove,createElement,body,execCopy,activeElement,document,nodeFromPoint,elementsFromPoint}
})()
const eventManager = (()=>{
	const create = (type,params) => {
		switch(type){
			case "keydown": return (new KeyboardEvent(type,params))
			case "click": return (new MouseEvent(type,params))
			case "mousedown": return (new MouseEvent(type,params))
			default: return (new CustomEvent(type,params))
		}
	}
	const sendToWindow = (event)=> window.dispatchEvent(event)
	return {create,sendToWindow}
})()

const miscReact = (()=>{
	const isReactRoot = (el) => {
		if(el.parentElement.classList.contains("branch")) return true
		return false
	}
	const getReactRoot = (el) => {
		if(!el) {
			const a = documentManager.body().querySelector("div.branch")
			return a&&a.firstElementChild
		}			
		if(isReactRoot(el) || !el.parentNode) return el
		const parentEl = el.parentNode
		return getReactRoot(parentEl)
	}	
	return {isReactRoot,getReactRoot}
})()
const miscUtil = (()=>{
	const winWifi = WinWifi(log,window.require,window.process,setInterval)
	const getBattery = typeof navigator.getBattery =="function"?(callback) => navigator.getBattery().then(callback):null
	const Scanner = window.Scanner
	const scannerProxy = ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,documentManager,scrollBy,eventManager})	
	window.ScannerProxy = scannerProxy
	const audioContext = () => {return new (window.AudioContext || window.webkitAudioContext)()}
	const audio = () => {return new Audio()}
	const fileReader = ()=> (new window.FileReader())
	return {winWifi,getBattery,scannerProxy,audioContext,fileReader}
})()
const overlayManager = OverlayManager({log,documentManager,windowManager})
const focusModule = FocusModule({log,documentManager,eventManager,windowManager,miscReact})
const dragDropModule = DragDropModule({log,documentManager,windowManager})
const metroUi = MetroUi({log,requestState,svgSrc,documentManager,focusModule,eventManager,overlayManager,dragDropModule,windowManager,miscReact,Image, miscUtil,StatefulComponent});
//customUi with hacks
const customMeasurer = () => window.CustomMeasurer ? [CustomMeasurer] : []
const customTerminal = () => window.CustomTerminal ? [CustomTerminal] : []
const innerHeight = () => window.innerHeight
const scrollBy = (x,y) => window.scrollBy(x,y)
const customUi = CustomUi({log,ui:metroUi,customMeasurer,customTerminal,svgSrc,overlayManager,windowManager,miscReact,miscUtil,StatefulComponent});
const updateManager = UpdateManager(log,window,metroUi, StatefulComponent)
const activeElement=()=>document.activeElement; //todo: remove

const virtualKeyboard = VirtualKeyboard({log,svgSrc,focusModule,eventManager,windowManager,miscReact,StatefulComponent})

//canvas
const util = Canvas.CanvasUtil()

const exchangeMix = options => canvas => CanvasExtra.ExchangeCanvasSetup(canvas,activeElement)
const ddMix = options => canvas => CanvasExtra.DragAndDropCanvasSetup(canvas,log,setInterval,clearInterval,addEventListener)
const canvasMods = [CanvasBaseMix(log,util),exchangeMix,CanvasExtraMix(log),ddMix]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods), sender)
const parentWindow = ()=> parent
const cryptoElements = CryptoElements({log,feedback,ui:metroUi,hwcrypto:window.hwcrypto,atob,parentWindow,StatefulComponent});
//transforms
const vDomAttributes = VDomAttributes(requestState)
const transforms = mergeAll([vDomAttributes.transforms,metroUi.transforms,customUi.transforms,cryptoElements.transforms,updateManager.transforms,canvas.transforms,virtualKeyboard.transforms])

const vDom = VDomCore(log,transforms,getRootElement)
const switchHost = SwitchHost(log,window)
const receiversList = [
    vDom.receivers,
    feedback.receivers,
	metroUi.receivers,    
	cryptoElements.receivers,
	focusModule.receivers,
	switchHost.receivers	
]
const composeUrl = () => {
    const port = parseInt(location.port)
    const hostPort = port && port != 80 ? location.hostname+":"+(port+1) : location.host
    return location.protocol+"//"+hostPort+"/sse"
}
const createEventSource = () => new EventSource(window.sseUrl||composeUrl()+"?"+(new Date()).getTime())

const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(window.requestAnimationFrame || (cb=>setTimeout(cb,16)), withState(log,[
    connection.checkActivate,
    vDom.checkActivate,
    canvas.checkActivate,
    metroUi.checkActivate,
    focusModule.checkActivate,
    dragDropModule.checkActivate,
	updateManager.checkActivate,
	virtualKeyboard.checkActivate
]))
