/*
import {useEffect,useContext,createContext,useState} from "../main/hooks"
import {EnqueuePatch, Identity,Patch} from "../main/util"

type Ack = {index:number} | undefined

const NoContext = createContext<Ack>(undefined)
const AckContext = createContext<Ack>(undefined)
AckContext.displayName = "AckContext"
const SenderContext = createContext<{enqueue:EnqueuePatch}|undefined>(undefined)
SenderContext.displayName = "SenderContext"

const nonMerged = (ack: Ack) => (aPatch: Patch) => !(aPatch && ack && aPatch.index <= ack.index)
export const useSender = () => useContext(SenderContext)


export const useSync = (identity: Identity): [Patch[], (patch: Patch) => void] => {
    const [patches,setPatches] = useState([])
    const sender = useSender()
    const enqueuePatch = useCallback(({onAck,...aPatch})=>{
        setPatches(aPatches=>[...aPatches,{onAck, ...aPatch, sentIndex: sender.enqueue(identity,aPatch)}])
    },[sender,identity])
    const ack = useContext(patches.length>0 ? AckContext : NoContext)
    useEffect(()=>{
        setPatches(aPatches => {
            if(aPatches.every(nonMerged(ack))) return aPatches
            aPatches.forEach(p=>nonMerged(ack)(p) || p.onAck && p.onAck())
            return aPatches.filter(nonMerged(ack))
        })
    },[ack])
    return [patches,enqueuePatch]
}*/