
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

const manageInterval = (f,t) => {
    const h = setInterval(f, t)
    return () => clearInterval(h)
}
const useSecCounter = ()=>{
    const [counter,setCounter] = useState(0)
    useEffect(()=>manageInterval(()=>setCounter(was=>was+1),1000),[setCounter])
}

const useWebsocket = ({url, stateToSend, onData, onClose})=>{
    useSecCounter()
    // connect/recv
    const [{ws,at},setConnected] = useState({})
    const isBadConnection = at && Date.now() > at + 5000 // needs counter (no ping -- no render)
    useEffect(()=>{
        if(!url || isBadConnection) return
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
            setConnected({})
            onClose && onClose()
        }
    }, [setConnected,url,onData,isBadConnection])
    // pong
    useEffect(() => ws?.send(""), [ws,at]) // 
    // send
    const mountedAt = useMemo(()=>Date.now(),[])
    const age = Math.floor((Date.now()-mountedAt)/30000)
    useEffect(() => ws?.send(stateToSend), [ws, stateToSend, age])
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
    const [{incoming, availability}, setState] = useState(()=>({incoming:{},availability:0}))
    const {receive} = useMemo(()=>Receiver({branchKey, transforms, setState}), [branchKey, transforms, setState])
    return {receive, incoming, availability}
}

const serializePairs = pairs => pairs.map(([k,v])=>`${k}\n ${v.replaceAll("\n","\n ")}`).join("\n")
const serializeState = ({sessionKey, isRoot, reloadKey, patches}) => reloadKey && sessionKey ? serializePairs([
    ["x-r-reload",reloadKey], ["x-r-session",sessionKey], ...(isRoot ? [["x-r-is-main","1"]] : []),
    ...patches.map(patch => ["x-r-patch",serializePairs(Object.entries(patch.headers).toSorted())])
]) : ""

const getIndex = patch => patch.headers["x-r-index"]
const getPath = patch => patch.headers["x-r-vdom-path"]
const eqBy = (was, will, by) => JSON.stringify(was.map(by)) === JSON.stringify(will.map(by))
const usePatchManager = () => {
    const [{reloadKey, patches, observers}, setState] = 
        useState(()=>({reloadKey: crypto.randomUUID(), nextPatchIndex:0, patches:[], observers: []}))
    const {enqueue, doAck, notifyObservers} = useMemo(()=>PatchManager(setState), [setState])
    useEffect(() => notifyObservers(patches, observers), [notifyObservers, patches, observers])
    return {enqueue, doAck, reloadKey, patches}
}
const PatchManager = setState => {
    const enqueue = (identity,patch,set) => setState(was => {
        const path = ctxToPath(identity)
        const headers = {
            ...patch.headers, value: patch.value, "x-r-vdom-path": path,
            "x-r-index": was.nextPatchIndex,
        }
        const skip = p => p.skipByPath && getPath(p)===getPath(patch)
        const patches = [...was.patches.filter(p=>!skip(p)), {...patch,headers,set}]
        return {...was, nextPatchIndex: was.nextPatchIndex+1, patches} 
    })
    const doAck = (clientKey, index) => setState(was => (
        was.reloadKey===clientKey ? {...was, patches: was.patches.filter(patch=>getIndex(patch) > index)} : was
    ))
    const notifyObservers = (patches, observers) => {
        const patchesByPath = Object.groupBy(patches, getPath)
        observers.forEach(observer => {
            const will = patchesByPath[observer.path]||[]
            observer.set(was => eqBy(was, will, getIndex) ? was : will)
        })
        const willObservers = Object.keys(patchesByPath).toSorted().map(path=>({path,set:patchesByPath[path][0].set}))
        setState(was => eqBy(was.observers, willObservers, o=>o.path) ? was : {...was, observers: willObservers})
    }
    return {enqueue, doAck, notifyObservers}
}

const SyncContext = createContext()
export const useSyncRoot = ({sessionKey,branchKey,reloadBranchKey,isRoot,transforms}) => {
    const {receive, incoming, availability} = useReceiverRoot({branchKey, transforms})
    const {enqueue, doAck, reloadKey, patches} = usePatchManager()
    const stateToSend = useMemo(
        () => serializeState({sessionKey, isRoot, reloadKey, patches}), 
        [sessionKey, isRoot, reloadKey, patches]
    )
    const url = branchKey && `/eventlog/${branchKey}`
    useWebsocket({ url, stateToSend, onData: receive, onClose: reloadBranchKey })
    
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
