
import {createElement,useState,useCallback} from "react"
import {spreadAll,mergeAll}    from "../main/util.js"
import {createSyncProviders} from "../../c4f/main/vdom-hooks.js"
import {weakCache} from "../../c4f/main/vdom-util.js"

const useEventListener = (el,evName,callback) => {
    useEffect(()=>{
        if(!callback || !el) return undefined
        el.addEventListener(evName,callback)
        return ()=>el.removeEventListener(evName,callback)
    }, [el,evName,callback])
}

const getLastKey = ctx => ctx && (ctx.key || getLastKey(ctx.parent))
function setupIncomingDiff(by,content,parent) {
    const {isInSet} = parent
    const setKey = isInSet && content["at"] && getLastKey(parent)
    const changes = Object.keys(content).map(key=>{
        const value = content[key]
        const trans = by[key]
        const handler = trans && value && (trans[value] || trans[value[0]])
        const will =
            handler ?
                (key==="tp" ? handler : handler({ value, parent, isInSet })) :
            key.substring(0,1)===":" || key === "at" ?
                setupIncomingDiff(by,value,{ key, parent, isInSet }) :
            key === "$set" ?
                setupIncomingDiff(by,value,{ parent, isInSet: true }) :
            value
        return value === will ? null : ({ [key]: will })
    }).concat(setKey && {key:setKey}).filter(i=>i)

    return changes.length>0 ? spreadAll(content, ...changes) : content
}

function update(object,spec){
    if("$set" in spec) return spec["$set"] // ? need deep compare
    let res = object
    Object.keys(spec).forEach(k=>{
        const willVal = update(object[k], spec[k])
        if(res[k] !== willVal){
            if(res === object) res = {...object}
            if(willVal === undefined){ delete res[k] }else res[k] = willVal
        }
    })
    return res
}

const resolveChildren = (o,keys) => keys.map(k=>elementWeakCache(o[k]))
export const elementWeakCache = weakCache(props=>{
    const {key,at:{tp,...at},...cProps} = props
    const childAt = props.at.identity ? Object.fromEntries(
        Object.entries(cProps).filter(([k,v])=>Array.isArray(v)).map(([k,v])=>[k, resolveChildren(cProps,v)])
    ) : {//lega:
        children: at.content && at.content[0] === "rawMerge" ? cProps :
          cProps.chl ? resolveChildren(cProps,cProps.chl) : at.content
    }
    return createElement(tp,{...at,key,...childAt}) // ? todo rm at.key
})

////

export const doCreateRoot = (parentNativeElement,reactElement) => {
    const rootNativeElement = parentNativeElement.ownerDocument.createElement("span")
    parentNativeElement.appendChild(rootNativeElement)
    const root = createRoot(rootNativeElement)
    root.render(reactElement)
    return () => {
        root.unmount()
        rootNativeElement.parentElement.removeChild(rootNativeElement)
    }
}

function IsolatedFrame({children,...props}){
    const [frameElement,ref] = setState()
    const [theBody,setBody] = setState()
    const frame = useCallback(()=>{
        const body = frameElement?.contentWindow?.document.body
        if(body.id) setBody(body)
    }, [frameElement,setBody])
    useAnimationFrame(frameElement, !theBody && frame)
    useEffect(() => theBody && doCreateRoot(theBody,createElement("span",{children})), [theBody,children])
    const srcdoc = '<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>'
    return createElement("iframe",{...props,srcdoc,ref})
}
//? fontSize = frameElement && frameElement.style.fontSize
// body.style.fontSize = fontSize || ""

const stateKey = "c4sessionKey"
export const useSession = () => {
    const [state, setState] = useState({})
    //
    useEffect(()=>{
        const sessionKey = sessionStorage.getItem(stateKey)
        sessionStorage.removeItem(stateKey)
        sessionKey && setState({sessionKey})
    },[setState])
    const saveSessionKey = useCallback(()=>sessionStorage.setItem(stateKey, sessionKey), [sessionKey])
    const win = element && element.ownerDocument.defaultView
    useEventListener(win, "beforeunload", saveSessionKey)
    //
    const login = useCallback((user, pass)=>{
        setState({})
        fin = sessionKey => setState(sessionKey ? {sessionKey} : {error:true})
        fetch("/connect",{ method: "POST", body: `${user}\n${pass}`, headers: {"x-r-auth":"check"}})
            .then(resp => fin(resp.headers.get("x-r-session")), error => fin(null))
    },[setState])
    //
    const {sessionKey} = state
    const reloadBranchKey = useCallback(()=>{
        const fin = (branchKey,sessionError) => setState(was => (
            sessionKey !== was.sessionKey ? was :
            branchKey ? {...was,branchKey} :
            sessionError ? setState({}) :
            !was.branchKey ? {...was,error:true} : was
        ))
        sessionKey && fetch("/connect",{ method: "POST", headers: {"x-r-auth":"branch","x-r-session":sessionKey}})
            .then(resp => fin(resp.headers.get("x-r-branch"), resp.headers.get("x-r-error")), error => fin(null,null))
    },[sessionKey,setState])
    //
    useEffect(reloadBranchKey, [reloadBranchKey])
    return {...state,login,reloadBranchKey}
}



const useWebsocket = ({url, pongMessage, onData, onClose})=>{
    const [theConnected,setConnected] = useState(null)
    const needConnected = !theConnected ? true : Date.now() < theConnected.at + 5000
    useEffect(()=>{
        if(!needConnected) return
        const ws = new WebSocket(url)
        setConnected({at:Date.now()})
        ws.onmessage = ev => {
            //console.log(ev)
            if(ev.data) onData(ev.data)
            const at = Date.now()
            setConnected(was => was && ws === was.ws && at < was.at+1000 ? was : {at,ws})
        }
        return ()=>{
            ws.close()
            setConnected(null)
            onClose && onClose()
        }
    }, [setConnected,url,onData,needConnected])
    useEffect(()=>{
        theConnected && theConnected.ws && theConnected.ws.send(pongMessage)
    },[theConnected,pongMessage])
}


export function SyncRoot({branchKey,reloadBranchKey,isRoot,transforms,sender}){
    const [theAvailability, setAvailability] = useState(0) //todo theAvailability to context
    const [theIncoming, setIncoming] = useState({})
    const onData = useCallback(data=>{
        const {availability,log} = JSON.parse(data)
        setAvailability(availability)
        const ctx = {branchKey} //todo? do we need `modify` here
        const
        const activeTransforms = mergeAll([{
            identity: { ctx: ctx => ctx },
            tp: {AckElement},
        }, transforms])
        setIncoming(was => log.reduce((res,d) => update(res, setupIncomingDiff(activeTransforms,d,ctx)), was || {}))
    },[branchKey,setAvailability,setIncoming,activeTransforms])
    //
    useWebsocket({ url: `/eventlog/${branchKey}`, pongMessage: isRoot ? "L":"", onData, onClose: reloadBranchKey })
    //
    const {clientKey} = sender
    const [ack, setAck] = useState()
    const doAck = useCallback((argClientKey, indexStr) => setAck(was=>{
        const index = parseInt(indexStr)
        return clientKey !== argClientKey || ack && was.index >= index ? was : {index}
    }),[clientKey,setAck])
    //
    createSyncProviders({ ack, doAck, isRoot, branchKey, sender, children: elementWeakCache(theIncoming) })
}

// todo: SenderContext for doAck; clientKey in sender
function AckElement({clientKey,index}){
    const {doAck} = useContext(SenderContext)
    useEffect(()=>doAck(clientKey,index),[doAck,clientKey,index])
}

export const changeIdOf = identityAt('change')
function LocationElement({value}){
    const [patches,enqueuePatch] = useSync(changeIdOf(identity))
    //relocateHash: location.href = "#"+data

}

function InpSender(sender){
    const enqueue = (identityCtx,patch) => {
        const sent = sender.send(identityCtx,patch)
        return parseInt(sent["x-r-index"])
    }

    return {enqueue, ctxToPath, busyFor: sender.busyFor, ack}
}
