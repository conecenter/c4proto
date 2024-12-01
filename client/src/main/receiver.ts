import { useEffect } from "./react";
import { Identity, identityAt, SyncAppContext } from "./util";

type ReceiverAppContext = { messageReceiver: (value: string) => void }
const deleteIdOf = identityAt("delete")
export const ToAlienMessagesElement = ({messages}:{messages?:React.ReactElement[]}) => messages??[]
export const ToAlienMessageElement = (
    {appContext:{useSender,messageReceiver},identity,value}:
    {appContext: SyncAppContext & ReceiverAppContext, messageKey: string, identity: Identity, value: string}
) => {
    const {enqueue,isRoot} = useSender()
    useEffect(()=>{
        if(!isRoot) return undefined 
        enqueue({identity: deleteIdOf(identity), skipByPath: true, value: ""})
        return () => messageReceiver(value) // local send at-most-once
    }, [enqueue,isRoot,identity,value])
    return []
}