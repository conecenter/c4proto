import {splitFirst}    from "../main/util"

export default function RequestState(sender,log){
	const callbacks = []
    const send = (ctx, target) => {
        const sent = sender.send(ctx, target)
        const branchKey = sent["X-r-branch"]
        const index = parseInt(sent["X-r-index"])
        //log(`req started ${branchKey},${index}`)
        const onFulfilled = response => {
			callbacks.filter(c=>c.branchKey == branchKey).forEach(c=>c.callback("Waiting For Server"))
			//log(`req status ${branchKey},${index},${response.status}`)
		}
        const onRejected = error => log(`req error ${branchKey},${index},${error}`)
        sent.response.then(onFulfilled,onRejected)
        return sent
    }
    const ackChange = data => {
        const [branchKey,body] = splitFirst(" ", data)
        const index = parseInt(body)
		callbacks.filter(c=>c.branchKey == branchKey).forEach(c=>c.callback(null))
       // log(`req handled ${branchKey},${index}`)
    }
    const receivers = ({ackChange})
	const reg = (o) =>{
		callbacks.push(o)		
		const unreg = () =>	{
			const index = callbacks.findIndex(c=>c == o)
			callbacks.splice(index,1)
		}		
		return {unreg}
	}
    return {send,receivers,reg}
}