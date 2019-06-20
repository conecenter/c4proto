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
import {CanvasBaseMix} from "../main/canvas-mix"
import * as CanvasExtra from "../extra/canvas-extra"
import CanvasExtraMix from "../extra/canvas-extra-mix"

import MetroUi       from "../extra/metro/metro-ui"
import MetroUiFilters   from "../extra/metro/metro-ui-filters"

import FocusModule		from "../extra/focus-module"

import OverlayManager from "../extra/overlay-manager"
//import RequestState from "../extra/request-state"
import VirtualKeyboard from "../extra/virtual-keyboard"
import WinWifi from "../extra/win-wifi-status"

import CryptoElements from "../extra/crypto-elements"

import CustomUi      from "../extra/custom/custom-ui"
import ScannerProxy  from "../extra/custom/android-scanner-proxy"
import ElectronUpdateManager from "../extra/custom/electron-update-manager"
//import SwitchHost from "../extra/custom/switchhost-module"
import "../test/scrollIntoViewIfNeeded"
import {Errors} from '../extra/metro/errors.js'

import React from 'react'
import ReactDOM from 'react-dom'
import autoBind from 'react-autobind'

const send = (url,options)=>fetch((window.feedbackUrlPrefix||"")+url, options)
const feedback = Feedback(localStorage,sessionStorage,document.location,send,setTimeout)
window.onhashchange = () => feedback.pong()
const requestState = VDomSender(feedback)
const log = (...v) => { if(!window.console) console.log("log",...v)}
const log2 = (...v) => { if(window.console) console.log("log",...v)}
//const requestState = sender//RequestState(sender,log)

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
	const screenRefresh = () => window.location.reload()
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

const miscReact = (()=>{
	const isReactRoot = (el) => true
	const getReactRoot = (el) => {
		if(!el){
			return documentManager.body().querySelector('span[tp="span"]')		
		}
		let e = el
		while(e){
			if(e.tagName == "BODY") return e.querySelector('span[tp="span"]')
			e = e.parentNode
		}
		return null
	}	
	return {isReactRoot,getReactRoot}
})()
const miscUtil = (()=>{
	let _winWifi
	let _scannerProxy
	const winWifi = () => {if(!_winWifi) _winWifi = WinWifi(log,window.require,window.process,setInterval); return _winWifi}
	const getBattery = typeof navigator.getBattery =="function"?(callback) => navigator.getBattery().then(callback):null
	const Scanner = () => window.Scanner
	const scannerProxy = () => {if(!_scannerProxy) window.ScannerProxy = _scannerProxy = ScannerProxy({Scanner,setInterval,clearInterval,log,innerHeight,documentManager,scrollBy}); return _scannerProxy}	
	const audioContext = () => {return new (window.AudioContext || window.webkitAudioContext)()}
	const audio = (f) => {return new Audio(f)}
	const fileReader = ()=> (new window.FileReader())
	const image = () => (new Image())
	return {winWifi,getBattery,scannerProxy,audioContext,fileReader,image,audio}
})()

const vDomAttributes = VDomAttributes(requestState)

const overlayManager = () => OverlayManager({log,documentManager,windowManager,getMountNode:()=>window.mountNode})
const focusModule = FocusModule({log,documentManager,windowManager})

//const dragDropModule = () => DragDropModule()
const metroUi = MetroUi({log:log2,requestState,documentManager,OverlayManager:overlayManager,windowManager,miscReact,miscUtil,StatefulComponent,vDomAttributes})
//customUi with hacks
const customMeasurer = () => window.CustomMeasurer ? [CustomMeasurer] : []
const customTerminal = () => window.CustomTerminal ? [CustomTerminal] : []
const customUi = CustomUi({log,ui:metroUi,customMeasurer,customTerminal,overlayManager,miscReact,miscUtil,StatefulComponent});
const electronUpdateManager = ElectronUpdateManager(log,window,metroUi, StatefulComponent)

const virtualKeyboard = VirtualKeyboard({log,btoa:window.btoa,windowManager,miscReact,StatefulComponent,reactPathConsumer:metroUi.reactPathConsumer})
const cryptoElements = CryptoElements({log,feedback,ui:metroUi,hwcrypto:window.hwcrypto,atob:window.atob,parentWindow:()=> window.parent,StatefulComponent});
const metroUiFilters = MetroUiFilters({log,StatefulComponent})

//canvas
const util = Canvas.CanvasUtil()
const exchangeMix = options => canvas => CanvasExtra.ExchangeCanvasSetup(canvas,documentManager.activeElement)
const ddMix = options => canvas => CanvasExtra.DragAndDropCanvasSetup(canvas,log,setInterval,clearInterval,addEventListener)
const canvasMods = [CanvasBaseMix(log,util),exchangeMix,CanvasExtraMix(log),ddMix]
const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods), requestState, log)

//transforms

const transforms = mergeAll([
    vDomAttributes.transforms,
	metroUi.transforms,
	customUi.transforms,
	cryptoElements.transforms,
	electronUpdateManager.transforms,
	canvas.transforms,
	virtualKeyboard.transforms,
	metroUiFilters.transforms	
])
window.transforms = transforms
window.React = React
window.ReactDOM = ReactDOM
const getMountNode = () => window.mountNode || document.body
const vDom = VDomCore(log,transforms,getMountNode)
//const switchHost = SwitchHost(log,window)
const errors = Errors(document)
const receiversList = [
    vDom.receivers,
    feedback.receivers,
	metroUi.receivers,    
	cryptoElements.receivers,
	focusModule.receivers,
	errors.receivers
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
    //dragDropModule.checkActivate,
	electronUpdateManager.checkActivate,
	virtualKeyboard.checkActivate,
	metroUiFilters.checkActivate
]))
