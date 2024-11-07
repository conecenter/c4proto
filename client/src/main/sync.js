
// @ts-check
import {createElement,useState,useCallback,useEffect,useContext,createContext,useMemo} from "react"
import {spreadAll,mergeAll,manageEventListener,weakCache,identityAt} from "../main/util.js"

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
const elementWeakCache = weakCache(props=>{
    const {key,at:{tp,...at},...cProps} = props
    const childAt = props.at.identity ? Object.fromEntries(
        Object.entries(cProps).filter(([k,v])=>Array.isArray(v)).map(([k,v])=>[k, resolveChildren(cProps,v)])
    ) : {//lega:
        children: at.content && at.content[0] === "rawMerge" ? cProps :
          cProps.chl ? resolveChildren(cProps,cProps.chl) : at.content
    }
    return createElement(tp,{...at,...childAt,key}) // ? todo rm at.key
})

export const ctxToPath = ctx => !ctx ? "" : ctxToPath(ctx.parent) + (ctx.key ? "/"+ctx.key : "")

////

const useWebsocket = ({url, pongMessage, onData, onClose})=>{
    const [theConnected,setConnected] = useState(null)
    const {ws,at} = theConnected || {}
    const needConnected = url && (!at || Date.now() < at + 5000)
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
    useEffect(() => ws?.send(pongMessage),[ws,at,pongMessage])
    return ws
}

const Receiver = ({branchKey, transforms, setState}) => {
    const tp = {AckElement,StatusElement}
    const activeTransforms = mergeAll([{ identity: { ctx: ctx => ctx }, tp }, transforms])
    const receive = data => {
        const {availability,log} = JSON.parse(data)
        const ctx = {branchKey}
        setState(was => ({
            incoming: log.reduce((res,d) => update(res, setupIncomingDiff(activeTransforms,d,ctx)), was.incoming),
            availability
        }))
    }
    return {receive}
}
const useReceiverRoot = ({branchKey, transforms}) => {
    const [state, setState] = useState(()=>({incoming:{},availability:0}))
    const receiver = useMemo(()=>Receiver({branchKey, transforms, setState}), [branchKey, transforms, setState])
    const {receive} = receiver
    const {incoming, availability} = state
    return {receive, incoming, availability}
}

const getIndex = patch => patch.headers["x-r-index"]
const getPath = patch => patch.headers["x-r-vdom-path"]
const eqBy = (was, will, by) => JSON.stringify(was.map(by)) === JSON.stringify(will.map(by))
const serialize = o => Object.entries(o).toSorted().flatMap(([k,v])=>v.split("\n").map(l=>`${k}:${l}`)).join("\n")
const sendOnce = (ws, patch) => {
    if(ws.lastSentIndex === undefined || ws.lastSentIndex < getIndex(patch)){
        ws.send(serialize(patch.headers))
        ws.lastSentIndex = getIndex(patch)
    }
}
const useSenderRoot = ({sessionKey, branchKey, ws}) => {
    const [state, setState] = 
        useState(()=>({reloadKey: crypto.randomUUID(), nextPatchIndex:0, patches:[], observers: []}))
    const sender = useMemo(()=>Sender({sessionKey, branchKey, ws, setState}), [sessionKey, branchKey, ws, setState])
    const {patches, observers} = state
    useEffect(() => sender.send(patches), [sender, patches])
    useEffect(() => sender.notifyObservers(patches, observers), [sender, patches, observers])
    return sender
}
const Sender = ({sessionKey, branchKey, ws, setState}) => {
    const enqueue = (identity,patch,set) => setState(was => {
        const path = ctxToPath(identity)
        const headers = {
            ...patch.headers, value: patch.value, "x-r-vdom-path": path,
            "x-r-reload": was.reloadKey, "x-r-index": was.nextPatchIndex, "x-r-alien-date": Date.now(),
        }
        const skip = p => p.skipByPath && getPath(p)===getPath(patch)
        const patches = [...was.patches.filter(p=>!skip(p)), {...patch,headers,set}]
        return {...was, nextPatchIndex: was.nextPatchIndex+1, patches} 
    })
    const doAck = (clientKey, index) => setState(was => (
        was.reloadKey===clientKey ? {...was, patches: was.patches.filter(patch=>getIndex(patch) > index)} : was
    ))
    const send = patches => {
        const last = patches.findLastIndex(patch=>!patch.defer)
        patches.forEach((patch, n) => n <= last && sendOnce(ws, patch))
    }
    const notifyObservers = (patches, observers) => {
        const patchesByPath = Object.groupBy(patches, getPath)
        observers.forEach(observer => {
            const will = patchesByPath[observer.path]||[]
            observer.set(was => eqBy(was, will, getIndex) ? was : will)
        })
        const willObservers = Object.keys(patchesByPath).toSorted().map(path=>({path,set:patchesByPath[path][0].set}))
        setState(was => eqBy(was.observers, willObservers, o=>o.path) ? was : {...was, observers: willObservers})
    }
    return {enqueue, doAck, send, notifyObservers}
}

const SyncContext = createContext()
export const useSyncRoot = ({sessionKey,branchKey,reloadBranchKey,isRoot,transforms}) => {
    const {receive, incoming, availability} = useReceiverRoot({branchKey, transforms})
    const url = branchKey && `/eventlog/${branchKey}`
    const pongMessage = useMemo(
        () => isRoot && sessionKey && branchKey ? serialize({"x-r-op": "online", value: "1"}) : "",
        [isRoot, sessionKey, branchKey]
    )
    const ws = useWebsocket({ url, pongMessage, onData: receive, onClose: reloadBranchKey })
    const {enqueue, doAck} = useSenderRoot({sessionKey, branchKey, ws})
    const [element, ref] = useState()
    const win = element?.ownerDocument.defaultView
    const provided = useMemo(()=>({enqueue,isRoot,doAck,win}), [enqueue,isRoot,doAck,win])
    const children = useMemo(()=>[
        createElement("span", {ref, key: "sync-ref"}),
        incoming.tp && createElement(SyncContext.Provider,{key: "sync-prov", value: provided, children: elementWeakCache(incoming)}),
    ], [provided,incoming,ref])
    return {children, enqueue, availability, branchKey}
}

function AckElement({clientKey,index}){
    const {doAck} = useContext(SyncContext)
    useEffect(()=>doAck(clientKey, parseInt(index)),[doAck,clientKey,index])
    return []
}

export const useSyncSimple = (incomingValue, identity) => {
    const [patches, setPatches] = useState([])
    const {enqueue} = useContext(SyncContext)
    const setValue = 
        useCallback(v => enqueue(identity, {value: v, skipByPath: true}, setPatches), [enqueue, identity, setPatches])
    const patch = patches.slice(-1)[0]
    const value = patch ? patch.value : incomingValue
    return {value, setValue, patches}
}

const locationChangeIdOf = identityAt('locationChange')
const useLocation = (incomingValue, identity) => {
    const {value, setValue} = useSyncSimple(incomingValue, identity)
    const {isRoot,win} = useContext(SyncContext)
    const rootWin = isRoot && win
    const location = rootWin?.location
    useEffect(()=>{
        if(location && !value) setValue(location.href)
        else if(location && location.href !== value) location.href = value //? = "#"+data
    }, [location, value, setValue])
    useEffect(() => manageEventListener(rootWin, "hashchange", ev => setValue(ev.newURL)), [rootWin,setValue])
}
function StatusElement({location, identity}){
    useLocation(location, locationChangeIdOf(identity))
    return []
}
