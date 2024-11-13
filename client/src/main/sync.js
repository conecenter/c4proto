
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
const getUntil = () => Date.now()+5000
const useWebsocket = ({url, stateToSend, onData, onClose})=>{
    const [{connIndex},setConnected] = useState(()=>({connIndex:0,until:getUntil()}))
    const [ws, setWs] = useState()
    useEffect(() => {
        if(!ws) return
        return manageInterval(() => {
            ws.send("")
            setConnected(was => Date.now() > was.until ? {until:getUntil(),connIndex:was.connIndex+1} : was)
        }, 1000)
    }, [setConnected,ws])
    // connect/recv
    useEffect(()=>{
        if(!url) return;
        const ws = new WebSocket(url)
        const unOpen = manageEventListener(ws, "open", ev => setWs(ws))
        const unMessage = manageEventListener(ws, "message", ev => {
            if(ev.data && onData) onData(ev.data)
            setConnected(was => ({...was,until:getUntil()}))
        })
        return ()=>{
            unOpen()
            unMessage()
            ws.close()
            setWs(null)
            onClose && onClose()
        }
    }, [setWs,setConnected,url,onData,connIndex])
    // send
    const [age, setAge] = useState(0)
    useEffect(()=>manageInterval(()=>setAge(was=>was+1), 30000), [setAge]) //online
    useEffect(() => { ws?.send(stateToSend) }, [ws, stateToSend, age])
}

const Receiver = ({branchKey, transforms, setState}) => {
    const tp = {StatusElement}
    const activeTransforms = mergeAll([{ identity: { ctx: ctx => ctx }, tp }, transforms])
    const receive = data => {
        const {availability,log,ack} = JSON.parse(data)
        const ctx = {branchKey}
        setState(was => ({
            incoming: log.reduce((res,d) => update(res, setupIncomingDiff(activeTransforms,d,ctx)), was.incoming),
            availability, ack,
        }))
    }
    return {receive}
}
const useReceiverRoot = ({branchKey, transforms}) => {
    const [{incoming, availability, ack}, setState] = useState(()=>({incoming:{},availability:0,ack:0}))
    const {receive} = useMemo(()=>Receiver({branchKey, transforms, setState}), [branchKey, transforms, setState])
    return {receive, incoming, availability, ack}
}

const serializeVals = vs => vs.map(v=>`-${v.replaceAll("\n","\n ")}`).join("\n")
const serializeState = ({isRoot,sessionKey,branchKey,patches}) => branchKey && sessionKey ? serializeVals([
    "bs1", isRoot?"m":"s", branchKey, sessionKey,
    serializeVals(patches.flatMap(patch => [patch.index,serializeVals(Object.entries(patch.headers).toSorted().flat())]))
]) : ""

const getIndex = patch => patch.index
const getPath = patch => patch.headers["x-r-vdom-path"]
const eqBy = (was, will, by) => JSON.stringify(was.map(by)) === JSON.stringify(will.map(by))
const usePatchManager = ack => {
    const [{patches, observers}, setState] = useState(()=>({nextPatchIndex: Date.now(), patches: [], observers: []}))
    const {enqueue, doAck, notifyObservers} = useMemo(()=>PatchManager(setState), [setState])
    useEffect(() => notifyObservers(patches, observers), [notifyObservers, patches, observers])
    useEffect(() => doAck(ack), [ack])
    return {enqueue, patches}
}
const PatchManager = setState => {
    const enqueue = (identity,patch,set) => setState(was => {
        const path = ctxToPath(identity)
        const index = was.nextPatchIndex
        const headers = {...patch.headers, value: patch.value, "x-r-vdom-path": path}
        const skip = p => p.skipByPath && getPath(p)===getPath(patch)
        const patches = [...was.patches.filter(p=>!skip(p)), {...patch,headers,set,index}]
        return {...was, nextPatchIndex: was.nextPatchIndex+1, patches} 
    })
    const doAck = index => setState(was => ({...was, patches: was.patches.filter(patch=>getIndex(patch) > index)}))
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
    const {receive, incoming, availability, ack} = useReceiverRoot({branchKey, transforms})
    const {enqueue, patches} = usePatchManager(ack)
    const stateToSend = useMemo(() => serializeState({isRoot,sessionKey,branchKey,patches}), [isRoot,sessionKey,branchKey,patches])
    const url = branchKey && sessionKey && "/eventlog"
    useWebsocket({ url, stateToSend, onData: receive, onClose: reloadBranchKey })
    const [element, ref] = useState()
    const win = element?.ownerDocument.defaultView
    const provided = useMemo(()=>({enqueue,isRoot,win}), [enqueue,isRoot,win])
    const children = useMemo(()=>[
        createElement("span", {ref, key: "sync-ref"}),
        incoming.tp && createElement(SyncContext.Provider,{key: "sync-prov", value: provided, children: elementWeakCache(incoming)}),
    ], [provided,incoming,ref])
    return {children, enqueue, availability, branchKey}
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
