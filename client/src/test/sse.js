
"use strict";

import SSEConnection from "../main/sse-connection"
import activate      from "../main/activator"

function TestShow(){
    let dataToShow
    function show(data){
        dataToShow = data
    }
    function checkActivate(state){
        document.body.textContent = dataToShow
        return state
    }
    const receivers = ({show})
    return ({receivers,checkActivate})
}
const send = fetch
const testShow = TestShow()
const receiversList = [testShow.receivers]
const createEventSource = () => new EventSource("http://localhost:8068/sse")
const reconnectTimeout = 5000
const checkActivate = testShow.checkActivate
const connection = SSEConnection({createEventSource,receiversList,checkActivate,reconnectTimeout,localStorage,sessionStorage,location,send})
activate(requestAnimationFrame, connection.checkActivate)
