
import {splitFirst}    from "../main/util"

export function ExampleRequestState(sender){
    const send = (ctx, target) => {
        const sent = sender.send(ctx, target)
        const branchKey = sent["X-r-branch"]
        const index = parseInt(sent["X-r-index"])
        console.log("sent",branchKey,index)
        return sent
    }
    const ackChange = data => {
        const [branchKey,body] = splitFirst(" ", data)
        const index = parseInt(body)
        console.log("handled",branchKey,index)
    }
    const receivers = ({ackChange})
    return {send,receivers}
}