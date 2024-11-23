
import {createElement,useState,useCallback,useEffect,useMemo} from "./hooks"
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

export type TypeTransforms = { [K: string]: React.FC<any>|string }
const Transformer = (transforms: TypeTransforms) => {
    const elementWeakCache: (props:Incoming) => object = weakCache(({at:{tp,...at},...cProps}:Incoming)=>{
        const childAt: ObjS<React.ReactElement[]> = Object.fromEntries(Object.keys(cProps).map(key => {
            if(!isChildOrderKey(key)) return undefined
            const children = (cProps[key] ?? assertNever("never")).map(kU => {
                const k = asString(kU)
                return elementWeakCache(isIncomingKey(k) && cProps[k] || assertNever("bad diff"))
            })
            return [key.substring(1), children]
        }).filter(it => it !== undefined))
        const constr = transforms[tp]
        return constr ? createElement(constr,{...at,...childAt}) : {tp,...at,...childAt}
    })
    return {elementWeakCache}
}

////

type ConnectionParams = {win: Window, url: string, onData: (value: string)=>void, stateToSend: ()=>string}
const ReConnection = (args: ConnectionParams) => {
    const {win} = args
    let connection = Connection(args)
    const hShort = win.setInterval(() => {
        connection.ping()
        if(connection.expired()){
            connection.close()
            connection = Connection(args)
        }
    }, 1000)
    const hLong = win.setInterval(()=>connection.doSend(), 30000)
    const close = () => {
        win.clearInterval(hShort)
        win.clearInterval(hLong)
        connection.close()
    }
    const doSend = ()=>connection.doSend()
    return {close, doSend}
}
const Connection = ({win,url,onData,stateToSend}:ConnectionParams) => {
    let ready = false
    let wasAt = Date.now()
    const ping = () => ready && ws.send("")
    const doSend = () => ready && ws.send(stateToSend())
    const expired = () => Date.now() - wasAt > 5000
    const onMessage = (ev: MessageEvent) => {
        if(ev.data && onData) onData(ev.data)
        wasAt = Date.now()
    }
    const onOpen = () => {
        ready = true
        doSend()
    }
    const ws = new win.WebSocket(url)
    ws.addEventListener("open", onOpen)
    ws.addEventListener("message", onMessage)
    const close = () => {
        ws.removeEventListener("message", onMessage)
        ws.removeEventListener("open", onOpen)
        try { ws.close() } catch(e){ console.trace(e) }
    }
    return {close, doSend, ping, expired}
}


////

type AckPatches = (index: number)=>void
type SyncRootState = { incoming: Incoming, availability: boolean, observerKey?: string }

const includes = <T>(big: ObjS<T>, small: ObjS<T>) => (
    Object.entries(small).every(([k,v]) => k in big && big[k] === v)
)
export const ifChanged = <T, S extends ObjS<T>>(f: ((was: S) => S)) => (was: S) => {
    const will = f(was)
    return includes(will, was) && includes(was, will) ? was : will
}
const Receiver = (setState: SetState<SyncRootState>) => {
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
const PatchManager = () => {
    let nextPatchIndex = Date.now()
    let patches: Patch[] = []
    let observed = patches
    const enqueue: EnqueuePatch = patch => {
        const skip = (p: Patch) => p.skipByPath && p.identity === patch.identity
        patches = [...patches.filter(p=>!skip(p)), {...patch, index: nextPatchIndex++}]
        notifyObservers()
    }
    const doAck: AckPatches = (index: number) => { // todo observerKey
        patches = patches.filter(p=>p.index > index)
        notifyObservers()
    }
    const notifyObservers = () => {
        [...observed,...patches].map(p => p.set).filter(distinct).forEach(set => {
            const will = patches.filter(p => p.set === set)
            set(was => getIndexStr(was) === getIndexStr(will) ? was : will)
        })
        observed = patches
    }
    const getPatches = () => patches
    return {enqueue, doAck, getPatches}
}

export const useMemoObj = <T extends ObjS<unknown>>(obj: T) => {
    const keys = Object.keys(obj).toSorted()
    return useMemo(()=>obj, [keys.join(" "), ...keys.map(k=>obj[k])])
}

const initSyncRootState = (): SyncRootState => {
    const patches: Patch[] = []
    return { availability: false, incoming: emptyIncoming, nextPatchIndex: Date.now(), patches, observed: patches }
}

export type PreLoginBranchContext = { appContext: { typeTransforms: TypeTransforms }, win: Window }
export type PreSyncBranchContext = 
    PreLoginBranchContext & { sessionKey: string, isRoot: boolean, branchKey: string, reloadBranchKey: ()=>void }
export type SyncBranchContext = PreSyncBranchContext & { enqueue: EnqueuePatch, doAck: AckPatches }

const SyncManager = ({typeTransforms, setState, isRoot, sessionKey, branchKey, win}:{}) => {
    const {elementWeakCache} = Transformer(typeTransforms)
    const {receive} = Receiver(setState)
    const {enqueue, doAck, getPatches} = PatchManager()
    const stateToSend = () => serializeState({isRoot, sessionKey, branchKey, patches: getPatches()})
    const {close, doSend} = ReConnection({win, url: "/eventlog", onData: receive, stateToSend}) // onIdle: reloadBranchKey
    const send = (patch: Patch) => {
        enqueue(patch)
        doSend()
    }
    return {enqueue: send, doAck, elementWeakCache, close}
}


export const useSyncRoot = ({sessionKey,branchKey,reloadBranchKey,isRoot,win,appContext}:PreSyncBranchContext) => {
    const {typeTransforms} = appContext
    const [{incoming, availability, observerKey}, setState] = useState<SyncRootState>(initSyncRootState)
    
    
    
    const {enqueue, doAck, elementWeakCache} = 
    
    
    const children = elementWeakCache(incoming)
    return {children, enqueue, doAck, availability}
}


export const useSyncSimple = (context: SyncBranchContext, identity: Identity): [Patch[], (value: string)=>void] => {
    const [patches, setPatches] = useState<Patch[]>([])
    const {enqueue} = context
    const setValue = useCallback((v: string) => {
        enqueue({identity, value: v, skipByPath: true, set: setPatches})
    }, [enqueue, identity, setPatches])
    return [patches, setValue]
}

type AckList = {observerKey: string, indexStr: string}[]
type UseLocationArgs = {context: SyncBranchContext, location: string, identity: Identity}
type UseAckListArgs = {context: SyncBranchContext, ackList?: AckList}
export type RootElementProps = {
    location: string, identity: Identity, failure: string, ackList: AckList, children: React.ReactElement[]
}

const locationChangeIdOf = identityAt('locationChange')
const useLocation = ({context, location: incomingValue, identity}: UseLocationArgs) => {
    const [patches, setValue] = useSyncSimple(context, locationChangeIdOf(identity))
    const value = mergeSimple(incomingValue, patches)
    const {isRoot,win} = context
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
const useAckList = ({context,ackList}:UseAckListArgs) => {
    const {doAck} = context
    useEffect(() => ackList && ackList.forEach(a=>doAck(a.observerKey,parseInt(a.indexStr))), [doAck,ackList])
}
export const useRoot = (prop: UseLocationArgs & UseAckListArgs) => {
    useLocation(prop)
    useAckList(prop)
}
