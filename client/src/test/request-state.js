
import {splitFirst}    from "../main/util"

export function ExampleRequestState(sender){
    const send = (ctx, target) => {
        const sent = sender.send(ctx, target)
        const branchKey = sent["X-r-branch"]
        const index = parseInt(sent["X-r-index"])
        console.log("req started",branchKey,index)
        const onFulfilled = response => console.log("req status",branchKey,index,response.status)
        const onRejected = error => console.log("req error",branchKey,index,error)
        sent.response.then(onFulfilled,onRejected)
        return sent
    }
    const ackChange = data => {
        const [branchKey,body] = splitFirst(" ", data)
        const index = parseInt(body)
        console.log("req handled",branchKey,index)
    }
    const receivers = ({ackChange})
    return {send,receivers}
}