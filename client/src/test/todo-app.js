
import Feedback      from "../main/feedback.js"
import activate      from "../main/activator.js"
import withState     from "../main/active-state.js"
import {VDomCore,VDomAttributes} from "../main/vdom-core.js"
import {VDomSender} from "../main/vdom-util.js"
import {mergeAll}    from "../main/util.js"

import {components as listComponents} from "../../c4f/main/vdom-list.js"
import {components as filterComponents} from "../../c4f/main/vdom-filter.js"

import {components as todoComponents} from "../test/todo.js"

function fail(data){ alert(data) }
const send = fetch
const feedback = Feedback(sessionStorage,document.location,send,setTimeout)
const sender = VDomSender(feedback)
const log = v => console.log(v)
const getRootElement = () => document.body
const vDomAttributes = VDomAttributes(sender)
const components = mergeAll([listComponents,filterComponents,todoComponents])
const transforms = mergeAll([vDomAttributes.transforms,{tp:components}])
const vDom = VDomCore(log,transforms,getRootElement)
const receiversList = [vDom.receivers,feedback.receivers,{fail}]
// todo receiversList removed

activate(requestAnimationFrame, withState(log,[
    connection.checkActivate,
    sessionReload.checkActivate,
    vDom.checkActivate,
]))
