import { useEffect } from "./react"
import { EnqueuePatch, Identity, identityAt, patchFromValue } from "./util"

const deleteIdOf = identityAt("delete")
type MessageReceiver = (value: string) => void
type ReceiverBranchContext = { enqueue: EnqueuePatch, isRoot: boolean }
type ToAlienMessageComponentsArgs = { messageReceiver: MessageReceiver, useSender: ()=>ReceiverBranchContext }
export const ToAlienMessageComponents = ({messageReceiver,useSender}:ToAlienMessageComponentsArgs) => {
    const ToAlienMessagesElement = ({messages}:{messages?:React.ReactElement[]}) => messages??[]
    const ToAlienMessageElement = ({ identity, value } : { identity: Identity, value: string }) => {
        const {enqueue,isRoot} = useSender()
        useEffect(()=>{
            if(!isRoot) return undefined 
            enqueue(deleteIdOf(identity), patchFromValue(""))
            return () => messageReceiver(value) // local send at-most-once
        }, [enqueue,isRoot,identity,value])
        return []
    }
    return {ToAlienMessagesElement,ToAlienMessageElement}
}
