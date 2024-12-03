import { useEffect } from "./react";
import { BranchContext, Identity, identityAt, patchFromValue, SyncAppContext } from "./util";

export type ReceiverAppContext = { messageReceiver: (value: string) => void }
const deleteIdOf = identityAt("delete")
export const ToAlienMessagesElement = ({messages}:{messages?:React.ReactElement[]}) => messages??[]
export const ToAlienMessageElement = (
    {branchContext:{enqueue,isRoot,messageReceiver},identity,value}:
    {branchContext: SyncAppContext & ReceiverAppContext & BranchContext, messageKey: string, identity: Identity, value: string}
) => {
    useEffect(()=>{
        if(!isRoot) return undefined 
        enqueue(deleteIdOf(identity), patchFromValue(""))
        return () => messageReceiver(value) // local send at-most-once
    }, [enqueue,isRoot,identity,value])
    return []
}