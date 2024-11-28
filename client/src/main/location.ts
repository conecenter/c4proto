
import {useEffect} from "./react"
import {Identity, manageEventListener, identityAt, mergeSimple, patchFromValue, SyncAppContext} from "./util"

const changeIdOf = identityAt('change')
export const LocationElement = (
    {appContext: {useSync,useSender}, value: incomingValue, identity}: 
    {appContext: SyncAppContext, value: string, identity: Identity }
) => {
    //console.log("loc",incomingValue)
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
