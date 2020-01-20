
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"

function TestShow(){
    let dataToShow
    function show(data){
        dataToShow = data
    }
    function checkActivate(){
        document.body.textContent = dataToShow
    }
    const receivers = ({show})
    return ({receivers,checkActivate})
}
const feedback = Feedback(sessionStorage,document.location,fetch,setTimeout)
//window.onhashchange = () => feedback.pong()
const testShow = TestShow()
const receiversList = [feedback.receivers, testShow.receivers]
const createEventSource = () => new EventSource(location.protocol+"//"+location.host+"/sse")
const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, [connection.checkActivate,testShow.checkActivate])
