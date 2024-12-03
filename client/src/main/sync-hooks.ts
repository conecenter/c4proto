
import {useEffect,useContext,createContext,useState,useCallback} from "./react"
import {assertNever,Patch,UnsubmittedPatch,UseSync,EnqueuePatch} from "./util"

const NoContext = createContext(0)
export const AckContext = createContext(0)
AckContext.displayName = "AckContext"
export const ABranchContext = createContext<{enqueue:EnqueuePatch}|undefined>(undefined)
ABranchContext.displayName = "ABranchContext"

const nonMerged = (ack: number) => (aPatch: Patch) => !(aPatch && aPatch.index <= ack)
export const useSender = () => useContext(ABranchContext) ?? assertNever("no BranchContext")

export const useSync: UseSync = identity => {
    const [patches,setPatches] = useState<Patch[]>([])
    const {enqueue} = useSender()
    const enqueuePatch = useCallback((aPatch: UnsubmittedPatch) => {
        const index = enqueue(identity, aPatch)
        setPatches(aPatches=>[...aPatches,{...aPatch, identity, index}])
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
