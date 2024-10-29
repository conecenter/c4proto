
import {createElement,useState,useCallback} from "react"

import {useSession,ClientRoot,changeIdOf,doCreateRoot} from "../main/sync.js"

const changeEventToPatch = ev => ({value: ev.target.value, skipByPath: true, retry: true})
export function useSyncInput(incomingValue, identity){
    const [patches,enqueuePatch] = useSync(identity)
    const onChange = useCallback(ev => enqueuePatch(changeEventToPatch(ev)), [enqueuePatch])
    const patch = patches.slice(-1)[0]
    const value = patch ? patch.value : incomingValue
    const changing = patch ? "1" : undefined
    return ({value,changing,onChange})
}
function ExampleInput({value}){
    const {value,onChange} = useSyncInput(value, changeIdOf(identity))
    return createElement("input", {value,onChange})
}

function Login({login,error}){
    const [user, setUser] = useState()
    const [pass, setPass] = useState()
    const onClick = useCallback(ev => login(user, pass), [login,user,pass])
    return createElement("div",{},[
        "Username ",
        createElement("input", {value: user, onChange: setUser, type:"text"}, null),
        ", password ",
        createElement("input", {value: pass, onChange: setPass, type:"password"}, null),
        " ",
        createElement("input", {type:"button", onClick, value: "sign in"}, null),
        error ? " FAILED" : ""
    ])
}

function ClientRoot({transforms,sender}){
    const [theElement, setElement] = useState()
    const {sessionKey,branchKey,error,login,reloadBranchKey} = useSession(theElement)
    return createElement("div", {ref:setElement}, [
        branchKey ? createElement(SyncRoot,{branchKey,reloadBranchKey,isRoot:true,transforms,sender}) :
        createElement(Login,{login,error},null)
    ])
}


(()=>{
    // todo: activeTransforms, sender
    const inpSender = InpSender(sender)
    const transforms = {Login,ExampleInput}
    doCreateRoot(document.body, createElement(ClientRoot,{transforms,sender:inpSender},null))
})()
/*
no SSEConnection, SessionReload
no .wasModificationError handling
no .loadKey making
move -- VDomAttributes, elementWeakCache-ver -- to c4e


*/