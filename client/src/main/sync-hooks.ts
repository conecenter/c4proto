
import {useEffect,useContext,createContext,useState,useCallback} from "./react"
import {assertNever,Identity,manageEventListener,ObjS,identityAt,BranchContext} from "./util"

type UnsubmittedLocalPatch = { skipByPath: boolean, value: string, headers?: ObjS<string>, onAck?: ()=>void }
export type LocalPatch = UnsubmittedLocalPatch & { sentIndex: number }

const NoContext = createContext(0)
export const AckContext = createContext(0)
AckContext.displayName = "AckContext"
export const ABranchContext = createContext<BranchContext|undefined>(undefined)
ABranchContext.displayName = "ABranchContext"

const nonMerged = (ack: number) => (aPatch: LocalPatch) => !(aPatch && aPatch.sentIndex <= ack)
export const useSender = () => useContext(ABranchContext) ?? assertNever("no BranchContext")

export const useSync = (identity: Identity): [LocalPatch[], (patch: UnsubmittedLocalPatch) => void] => {
    const [patches,setPatches] = useState<LocalPatch[]>([])
    const {enqueue} = useSender()
    const enqueuePatch = useCallback((aPatch: UnsubmittedLocalPatch) => {
        const sentIndex = enqueue({...aPatch, identity})
        setPatches(aPatches=>[...aPatches,{...aPatch, identity, sentIndex}])
    },[enqueue,identity])
    const ack = useContext(patches.length>0 ? AckContext : NoContext)
    useEffect(()=>{
        setPatches(aPatches => {
            if(aPatches.every(nonMerged(ack))) return aPatches
            aPatches.forEach(p=>nonMerged(ack)(p) || p.onAck && p.onAck())
            return aPatches.filter(nonMerged(ack))
        })
    },[ack])
    return [patches,enqueuePatch]
}

export const mergeSimple = (value: string, patches: LocalPatch[]): string => {
    const patch = patches.slice(-1)[0]
    return patch ? patch.value : value
}
export const patchFromValue = (value: string): UnsubmittedLocalPatch => ({ value, skipByPath: true })
const changeIdOf = identityAt('change')
export const LocationElement = ({value: incomingValue, identity}:{value: string, identity: Identity }) => {
    const [patches, enqueuePatch] = useSync(changeIdOf(identity))
    const value = mergeSimple(incomingValue, patches)
    const {isRoot,win} = useSender()
    const rootWin = isRoot ? win : undefined
    const location = rootWin?.location
    useEffect(()=>{
        if(location) enqueuePatch(patchFromValue(location.href))
    }, [location, enqueuePatch])
    useEffect(()=>{
        if(location && value && location.href !== value) location.href = value //? = "#"+data
    }, [location, value, enqueuePatch])
    useEffect(() => {
        return !rootWin ? undefined : 
            manageEventListener(rootWin, "hashchange", ev => enqueuePatch(patchFromValue(ev.newURL)))
    }, [rootWin, enqueuePatch])
    return []
}
