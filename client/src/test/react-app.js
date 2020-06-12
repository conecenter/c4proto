
import React         from 'react'
import ReactDOM      from 'react-dom'
import update        from 'immutability-helper'
import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import SessionReload from "../main/session-reload"
import activate      from "../main/activator"
import withState     from "../main/active-state"
import {VDomCore,VDomAttributes} from "../main/vdom-core"
import {VDomSender,pairOfInputAttributes} from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../main/canvas-manager"
import {ExampleAuth,ExampleComponents} from "../test/vdom-components"
import {ExampleRequestState} from "../test/request-state"
//import CanvasExtraMix from "../extra/canvas-extra-mix"
import {CanvasBaseMix} from "../main/canvas-mix"
import {sortTransforms} from "../main/vdom-sort.js"


function fail(data){ alert(data) }

const send = fetch

const feedback = Feedback(sessionStorage,document.location,send,setTimeout)
const sessionReload = SessionReload(localStorage,sessionStorage,location,Math.random)
//window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback)
const exampleRequestState = ExampleRequestState(sender)

const log = v => console.log(v)
const getRootElement = () => document.body

const util = Canvas.CanvasUtil()

const exchangeMix = options => canvas => Canvas.ExchangeCanvasSetup(canvas)
const canvasMods = [CanvasBaseMix(log,util),exchangeMix/*,CanvasExtraMix(log)*/]

const canvas = CanvasManager(React,Canvas.CanvasFactory(util, canvasMods), sender, log)

const vDomAttributes = VDomAttributes(exampleRequestState)
const exampleComponents = ExampleComponents(vDomAttributes.transforms.tp)
const exampleAuth = ExampleAuth(pairOfInputAttributes)
const transforms = mergeAll([vDomAttributes.transforms, exampleComponents.transforms, exampleAuth.transforms, canvas.transforms, sortTransforms])

const vDom = VDomCore(React,ReactDOM,update,log,transforms,getRootElement)

const receiversList = [vDom.receivers,feedback.receivers,{fail},exampleRequestState.receivers]
const createEventSource = () => new EventSource(location.protocol+"//"+location.host+"/sse")

const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, withState(log,[
    connection.checkActivate,
    sessionReload.checkActivate,
    vDom.checkActivate,
    canvas.checkActivate
]))
