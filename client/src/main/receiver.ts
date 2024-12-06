import { useEffect } from "./react"
import { BranchContext, Identity, identityAt, patchFromValue } from "./util"

const deleteIdOf = identityAt("delete")
export const ToAlienMessageComponents = (messageReceiver: (value: string) => void) => {
    const ToAlienMessagesElement = ({messages}:{messages?:React.ReactElement[]}) => messages??[]
    const ToAlienMessageElement = (
        { branchContext: {enqueue,isRoot}, identity, value } :
        { branchContext: BranchContext, messageKey: string, identity: Identity, value: string }
    ) => {
        useEffect(()=>{
            if(!isRoot) return undefined 
            enqueue(deleteIdOf(identity), patchFromValue(""))
            return () => messageReceiver(value) // local send at-most-once
        }, [enqueue,isRoot,identity,value])
        return []
    }
    return {ToAlienMessagesElement,ToAlienMessageElement}
}