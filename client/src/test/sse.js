
"use strict";

import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"

function TestShow(){
    var dataToShow

    function show(data){
        // console.log((new Date).getTime() % 10000,event.data % 100)
        dataToShow = data //+ " " + connectionKeyState + " " + sessionKey(function(){})
    }
    function animationFrame(){
        document.body.textContent = dataToShow
        requestAnimationFrame(animationFrame)
    }

    requestAnimationFrame(animationFrame)

    return ({show})
}
const feedback = Feedback(localStorage,sessionStorage,()=>document.location)
const receivers = [feedback.receivers, TestShow()]
SSEConnection(()=>new EventSource("http://localhost:8068/sse"),receivers,5)
