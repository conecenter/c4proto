
import {createElement,useState,useCallback,useEffect,useContext,createContext,useMemo} from "./hooks"
import {manageEventListener,weakCache,SetState,assertNever,getKey,asObject,asString,ObjS,Identity,mergeSimple,EnqueuePatch,Patch} from "./util"

const asArray = (u: unknown): unknown[] => Array.isArray(u) ? u : assertNever("bad array")
const asBoolean = (u: unknown) => typeof u === "boolean" ? u : assertNever("bad boolean")
const jsonParse = (data: string): unknown => JSON.parse(data)

////

type IncomingAt = ObjS<unknown> & {tp:string}
type Incoming = { at: IncomingAt, [x: `@${string}`]: unknown[], [x: `:${string}`]: Incoming }
const emptyIncoming: Incoming = {at:{tp:"span"}}

const isIncomingKey = (key: string): key is `:${string}` => key.startsWith(":")
const isChildOrderKey = (key: string): key is `@${string}` => key.startsWith("@")

const resolve = (identity: Identity, key: string) => identity+'/'+key
export const ctxToPath = (ctx: Identity): string => ctx
export const identityAt = (key: string): (identity: Identity)=>Identity => identity => resolve(identity, key)

const asObjectOrArray = (u: unknown): {}|unknown[] => typeof u === "object" && u ? u : assertNever("bad object")
const update = (inc: Incoming, spec: ObjS<unknown>, pKey: string, pIdentity: string): Incoming => {
    const res = {...inc}
    Object.keys(spec).forEach(key=>{
        //console.log("U",key,spec[key])
        const sValue: ObjS<unknown>|unknown[] = asObjectOrArray(spec[key])
        const value = Array.isArray(sValue) ? sValue : sValue["$set"] ?? sValue
        const cIdentity = pIdentity === "root" ? "" : resolve(pIdentity, key)
        if(isIncomingKey(key)) 
            res[key] = update((sValue !== value || !inc ? undefined : inc[key]) ?? emptyIncoming, asObject(value), key, cIdentity)
        else if(isChildOrderKey(key)) res[key] = asArray(value)
        else if(key === "at") res[key] = {...emptyIncoming.at,...asObject(value), key: pKey, identity: cIdentity}
        else assertNever(`bad key in diff (${key})`)
    })
    return res
}

export type Transforms = { tp: TypeTransforms }
type TypeTransforms = { [K: string]: React.FC<any>|string }
const Transformer = (transforms: Transforms) => {
    const activeTransforms: TypeTransforms = {span:"span",AckElement,...transforms.tp}
    const elementWeakCache: (props:Incoming) => object = weakCache(({at:{tp,...at},...cProps}:Incoming)=>{
        const childAt: ObjS<React.ReactElement[]> = Object.fromEntries(Object.keys(cProps).map(key => {
            if(!isChildOrderKey(key)) return undefined
            const children = (cProps[key] ?? assertNever("never")).map(kU => {
                const k = asString(kU)
                return elementWeakCache(isIncomingKey(k) && cProps[k] || assertNever("bad diff"))
            })
            return [key.substring(1), children]
        }).filter(it => it !== undefined))
        const constr = activeTransforms[tp]
        return constr ? createElement(constr,{...at,...childAt}) : {tp,...at,...childAt}
    })
    return {elementWeakCache}
}

////

const manageInterval = (win: Window, handler: ()=>void, timeout: number) => {
    const {setInterval, clearInterval} = win
    handler()
    const h = setInterval(handler, timeout)
    return () => clearInterval(h)
}

type WebSocketState = {connectionCounter: number, ws?: WebSocket}
type WebSocketParams = {url: string, onData: (value: string)=>void, onIdle: ()=>void, win: Window|null }
const WebSocketManager = ({setState, url, onData, onIdle, win}: WebSocketParams & {setState: SetState<WebSocketState>}) => {
    let wasAt = Date.now()
    const check = () => {
        if(Date.now() - wasAt > 5000){
            onIdle()
            setState(was => ({connectionCounter:was.connectionCounter+1}))
        }
    }
    const onMessage = (ws: WebSocket, ev: MessageEvent) => {
        if(ev.data && onData) onData(ev.data)
        if(Date.now() - wasAt > 1000){
            ws.send("")
            wasAt = Date.now()
        }
    }
    const manage = () => {
        if(!win) return
        const {WebSocket} = win
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

const useWebSocket = ({win, url, stateToSend, onData, onIdle}: WebSocketParams & {stateToSend: string })=>{
    const [{ws,connectionCounter},setState] = useState<WebSocketState>({connectionCounter:0})
    const {manage,check} = useMemo(()=>WebSocketManager({setState,url,onData,onIdle,win}),[setState,url,onData,onIdle,win])
    useEffect(() => win ? manageInterval(win,check, 1000) : undefined, [win,check])
    useEffect(() => manage(), [manage,connectionCounter])
    useEffect(() => win ? manageInterval(win,()=>ws?.send(stateToSend), 30000) : undefined, [win, ws, stateToSend])
}

////

type AckPatches = (observerKey: string, index: number)=>void
type SyncContext = {enqueue: EnqueuePatch, isRoot: boolean, win: Window|null, doAck: AckPatches}
type SyncRootState = {
    incoming: Incoming, availability: boolean, observerKey?: string,
    nextPatchIndex: number, patches: Patch[], observed: Patch[]
}

const includes = <T>(big: ObjS<T>, small: ObjS<T>) => (
    Object.entries(small).every(([k,v]) => k in big && big[k] === v)
)
export const ifChanged = <T, S extends ObjS<T>>(f: ((was: S) => S)) => (was: S) => {
    const will = f(was)
    return includes(will, was) && includes(was, will) ? was : will
}
const Receiver = ({branchKey, setState}: {branchKey: string, setState: SetState<SyncRootState>}) => {
    const receive = (data: string) => {
        const message = asObject(jsonParse(data))
        const log = asArray(getKey(message,"log"))
        const availability = asBoolean(getKey(message,"availability"))
        const observerKey = asString(getKey(message,"observerKey"))
        setState(ifChanged(was => {
            const incoming = log.reduce((res:Incoming,d) => {
                //console.log("I", res, d)
                const setCh = "@children" in res ? {} : {"@children":{"$set":[":root"]}}
                return update(res, {":root":d,...setCh}, "root", "root")
            }, was.incoming)
            return {...was, availability, observerKey, incoming}
        }))
    }
    return {receive}
}

const serializeVals = (vs: Array<string|number>) => vs.map(v=>{
    //if(!{string:1,number:1}[typeof v]) console.log(vs)
    return `-${v}`.replaceAll("\n","\n ")
}).join("\n")
const serializePatch = (patch: Patch) => {
    const h: ObjS<string> = {...patch.headers, value: patch.value, "x-r-vdom-path": ctxToPath(patch.identity)}
    return serializeVals(Object.keys(h).toSorted().flatMap(k => [k, h[k] ?? assertNever("never")]))
}
const serializePatches = (patches: Patch[]) => serializeVals(
    patches.flatMap(patch => [patch.index, serializePatch(patch)])
)
const serializeState = (
    {isRoot,sessionKey,branchKey,patches}: {isRoot: boolean, sessionKey: string, branchKey: string, patches: Array<Patch>}
) => branchKey && sessionKey ? serializeVals(
    ["bs1", isRoot?"m":"s", branchKey, sessionKey, serializePatches(patches)]
) : ""

const getIndexStr = (patches: Patch[]) => patches.map(p => p.index).join(" ")
const distinct = <T>(value: T, index: number, array: T[]): boolean => array.indexOf(value) === index
const PatchManager = (setState: SetState<SyncRootState>) => {
    const enqueue: EnqueuePatch = patch => setState(was => {
        const index = was.nextPatchIndex
        const skip = (p: Patch) => p.skipByPath && p.identity === patch.identity
        const patches = [...was.patches.filter(p=>!skip(p)), {...patch,index}]
        return {...was, nextPatchIndex: was.nextPatchIndex+1, patches} 
    })
    const doAck: AckPatches = (observerKey, index) => setState(
        was => was.observerKey === observerKey ? {...was, patches: was.patches.filter(p=>p.index > index)} : was
    )
    const notifyObservers = (patches: Patch[], observed: Patch[]) => {
        observed.map(p => p.set).filter(distinct).forEach(set => {
            const will = patches.filter(p => p.set === set)
            set(was => getIndexStr(was) === getIndexStr(will) ? was : will)
        })
        setState(was => was.observed === patches ? was : {...was, observed: patches})
    }
    return {enqueue, doAck, notifyObservers}
}

const initSyncRootState = (): SyncRootState => {
    const patches: Patch[] = []
    return { availability: false, incoming: emptyIncoming, nextPatchIndex: Date.now(), patches, observed: patches }
}
const failNoContext = () => { throw Error("no context") }
const SyncContext = createContext<SyncContext>({ enqueue: failNoContext, isRoot: false, doAck: failNoContext, win: null })
export const useSyncRoot = (
    {sessionKey,branchKey,reloadBranchKey,isRoot,transforms}:
    {sessionKey: string, branchKey: string, reloadBranchKey: ()=>void, isRoot: boolean, transforms: Transforms}
) => {
    const [{incoming, availability, patches, observed}, setState] = useState<SyncRootState>(initSyncRootState)
    const {elementWeakCache} = useMemo(()=>Transformer(transforms), [transforms])
    const {receive} = useMemo(()=>Receiver({branchKey, setState}), [branchKey, setState])
    const {enqueue, doAck, notifyObservers} = useMemo(()=>PatchManager(setState), [setState])
    useEffect(() => notifyObservers(patches, observed), [notifyObservers, patches, observed])
    const stateToSend = useMemo(() => serializeState({
        isRoot,sessionKey,branchKey,patches
    }),[isRoot,sessionKey,branchKey,patches])
    const [element, ref] = useState<HTMLElement>()
    const win = element?.ownerDocument.defaultView ?? null
    useWebSocket({ url: "/eventlog", stateToSend, onData: receive, onIdle: reloadBranchKey, win })
    const provided: SyncContext = useMemo(()=>({enqueue,isRoot,win,doAck}), [enqueue,isRoot,win,doAck])
    const children = useMemo(()=>[
        createElement("span", {ref, key: "sync-ref"}),
        createElement(SyncContext.Provider,{key: "sync-prov", value: provided, children: elementWeakCache(incoming)}),
    ], [provided,incoming,ref])
    return {children, enqueue, availability, branchKey}
}


export const useSyncSimple = (identity: Identity): [Patch[], (value: string)=>void] => {
    const [patches, setPatches] = useState<Patch[]>([])
    const {enqueue} = useContext(SyncContext)
    const setValue = useCallback((v: string) => {
        enqueue({identity, value: v, skipByPath: true, set: setPatches})
    }, [enqueue, identity, setPatches])
    return [patches, setValue]
}

const locationChangeIdOf = identityAt('locationChange')
export const useLocation = ({location: incomingValue, identity}:{location: string, identity: Identity}) => {
    const [patches, setValue] = useSyncSimple(locationChangeIdOf(identity))
    const value = mergeSimple(incomingValue, patches)
    const {isRoot,win} = useContext(SyncContext)
    const rootWin = isRoot ? win : undefined
    const location = rootWin?.location
    useEffect(()=>{
        if(location) setValue(location.href)
    }, [location, setValue])
    useEffect(()=>{
        if(location && value && location.href !== value) location.href = value //? = "#"+data
    }, [location, value, setValue])
    useEffect(() => {
        return !rootWin ? undefined : manageEventListener(rootWin, "hashchange", ev => setValue(ev.newURL))
    }, [rootWin,setValue])
}

const AckElement = ({observerKey, indexStr}:{observerKey: string, indexStr: string}): React.ReactElement[] => {
    const {doAck} = useContext(SyncContext)
    useEffect(()=>doAck(observerKey,parseInt(indexStr)), [doAck,observerKey,indexStr])
    return []
}
