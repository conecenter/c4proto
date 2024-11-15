
// @ts-check
import {createElement,useState,useCallback,useEffect,useContext,createContext,useMemo} from "./hooks"
import {spreadAll,mergeAll,manageEventListener,weakCache,identityAt} from "./util"

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
    f()
    const h = setInterval(f, t)
    return () => clearInterval(h)
}

const WebSocketManager = ({setState,url,onData,onIdle}) => {
    let wasAt = Date.now()
    const check = () => {
        if(Date.now() - wasAt > 5000){
            onIdle()
            setState(was => ({connectionCounter:was.connectionCounter+1}))
        }
    }
    const onMessage = (ws,ev) => {
        if(ev.data && onData) onData(ev.data)
        if(Date.now() - wasAt > 1000){
            ws.send("")
            wasAt = Date.now()
        }
    }
    const manage = () => {
        const ws = new WebSocket(url)
        const unOpen = manageEventListener(ws, "open", ev => setState(was => ({...was,ws})))
        const unMessage = manageEventListener(ws, "message", ev => onMessage(ws,ev))
        return ()=>{
            unOpen()
            unMessage()
            ws.close()
        }
    }
    return {manage,check}
}

const useWebSocket = ({url, stateToSend, onData, onIdle})=>{
    const [{ws,connectionCounter},setState] = useState({connectionCounter:0,ws:null})
    const {manage,check} = useMemo(()=>WebSocketManager({setState,url,onData,onIdle}),[setState,url,onData,onIdle])
    useEffect(() => manageInterval(check, 1000), [check])
    useEffect(() => manage(), [manage,connectionCounter])
    useEffect(() => manageInterval(()=>ws?.send(stateToSend), 30000), [ws, stateToSend])
}

const includes = (big,small) => Object.entries(small).every(([k,v]) => k in big && big[k] === v)
export const ifChanged = f => was => {
    const will = f(was)
    return includes(will, was) && includes(was, will) ? was : will
}
const Receiver = ({branchKey, transforms, setState}) => {
    const activeTransforms = mergeAll([{ identity: { ctx: ctx => ctx }, tp: {AckElement} }, transforms])
    const receive = data => {
        const {availability,log,observerKey} = JSON.parse(data)
        const ctx = {branchKey}
        setState(ifChanged(was => ({
            ...was, availability, observerKey,
            incoming: log.reduce((res,d) => update(res, setupIncomingDiff(activeTransforms,d,ctx)), was.incoming),
        })))
    }
    return {receive}
}

const serializableTypes = {string:1,number:1}
const serializeVals = vs => vs.map(v=>{
    if(!serializableTypes[typeof v]) console.log(vs)
    return `-${v}`.replaceAll("\n","\n ")
}).join("\n")
const serializeState = ({isRoot,sessionKey,branchKey,patches}) => branchKey && sessionKey ? serializeVals([
    "bs1", isRoot?"m":"s", branchKey, sessionKey,
    serializeVals(patches.flatMap(patch => [patch.index,serializeVals(
        Object.entries({...patch.headers, value: patch.value, "x-r-vdom-path": patch.path}).toSorted().flat()
    )]))
]) : ""

const eqBy = (was, will, by) => JSON.stringify(was.map(by)) === JSON.stringify(will.map(by))
const PatchManager = (setState: React.Dispatch<React.SetStateAction<SyncRootState>>) => {
    const enqueue = (identity,patch,set) => setState(was => {
        const path = ctxToPath(identity)
        const index = was.nextPatchIndex
        const skip = p => p.skipByPath && p.path===path
        const patches = [...was.patches.filter(p=>!skip(p)), {...patch,set,index,path}]
        return {...was, nextPatchIndex: was.nextPatchIndex+1, patches} 
    })
    const doAck = (observerKey,index) => setState(
        was => was.observerKey === observerKey ? {...was, patches: was.patches.filter(p=>p.index > index)} : was
    )
    const notifyObservers = (patches: Array<Patch>, observers) => {
        const patchesByPath = Object.groupBy(patches, p=>p.path)
        observers.forEach(observer => {
            const will = patchesByPath[observer.path] ?? []
            observer.set(was => eqBy(was, will, p => p.index) ? was : will)
        })
        const willObservers = Object.keys(patchesByPath).toSorted().map(path=>({path,set:patchesByPath[path][0].set}))
        setState(was => eqBy(was.observers, willObservers, o=>o.path) ? was : {...was, observers: willObservers})
    }
    return {enqueue, doAck, notifyObservers}
}

interface Patch {
    index: number
    path: string
    set
}

interface SyncRootState {
    incoming
    availability
    observerKey
    nextPatchIndex
    patches: Array<Patch>
    observers
}

const SyncContext = createContext({enqueue:null,isRoot:false,win:null,doAck:null})
export const useSyncRoot = ({sessionKey,branchKey,reloadBranchKey,isRoot,transforms}) => {
    const [{incoming, availability, patches, observers}, setState] = useState<SyncRootState>(()=>({ 
        incoming: {}, availability: false, observerKey: null, nextPatchIndex: Date.now(), patches: [], observers: [] 
    }))
    const {receive} = useMemo(()=>Receiver({branchKey, transforms, setState}), [branchKey, transforms, setState])
    const {enqueue, doAck, notifyObservers} = useMemo(()=>PatchManager(setState), [setState])
    useEffect(() => notifyObservers(patches, observers), [notifyObservers, patches, observers])
    const stateToSend = useMemo(() => serializeState({
        isRoot,sessionKey,branchKey,patches
    }),[isRoot,sessionKey,branchKey,patches])
    useWebSocket({ url: "/eventlog", stateToSend, onData: receive, onIdle: reloadBranchKey })
    const [element, ref] = useState(null)
    const win = element?.ownerDocument.defaultView
    const provided = useMemo(()=>({enqueue,isRoot,win,doAck}), [enqueue,isRoot,win,doAck])
    const children = useMemo(()=>[
        createElement("span", {ref, key: "sync-ref"}),
        incoming.at && createElement(SyncContext.Provider,{key: "sync-prov", value: provided, children: elementWeakCache(incoming)}),
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
export const useLocation = ({location:incomingValue, identity}) => {
    const {value, setValue} = useSyncSimple(incomingValue, locationChangeIdOf(identity))
    const {isRoot,win} = useContext(SyncContext)
    const rootWin = isRoot && win
    const location = rootWin?.location
    useEffect(()=>{
        if(location && !value) setValue(location.href)
        else if(location && location.href !== value) location.href = value //? = "#"+data
    }, [location, value, setValue])
    useEffect(() => manageEventListener(rootWin, "hashchange", ev => setValue(ev.newURL)), [rootWin,setValue])
}

function AckElement({observerKey,indexStr}){
    const {doAck} = useContext(SyncContext)
    useEffect(()=>doAck(observerKey,parseInt(indexStr)), [doAck,observerKey,indexStr])
}
