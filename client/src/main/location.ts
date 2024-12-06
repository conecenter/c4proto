
import {useEffect} from "./react"
import {Identity, manageEventListener, identityAt, mergeSimple, patchFromValue, UseSync} from "./util"

const changeIdOf = identityAt('change')
type LocationBranchContext = { isRoot: boolean, win: Window }
export const LocationComponents = ({useSender,useSync}: {useSender: ()=>LocationBranchContext, useSync: UseSync}) => {
    const LocationElement = ({value: incomingValue, identity}: {value: string, identity: Identity}) => {
        const {isRoot,win} = useSender()
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
    return {LocationElement}
}