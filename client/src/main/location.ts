
import {useEffect} from "./react"
import {Identity, manageEventListener, identityAt, mergeSimple, patchFromValue, SyncAppContext, BranchContext} from "./util"

const changeIdOf = identityAt('change')
export const LocationElement = (
    {branchContext: {useSync,isRoot,win}, value: incomingValue, identity}: 
    {branchContext: SyncAppContext & BranchContext, value: string, identity: Identity }
) => {
    //console.log("loc",incomingValue)
    const [patches, enqueuePatch] = useSync(changeIdOf(identity))
    const value = mergeSimple(incomingValue, patches)
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
