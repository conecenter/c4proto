
import {patchFromValue,ctxToPath}    from "../main/util.js"

/********* sync input *********************************************************/

const eventToPatch = (e) => ({headers: e.target.headers, value: e.target.value, skipByPath: true, retry: true})

function useSyncInput(patches,enqueuePatch,incomingValue,deferSend){
    const [lastPatch,setLastPatch] = useState()
    const defer = deferSend(!!lastPatch)
    const onChange = useCallback(event => {
        const patch = eventToPatch(event)
        enqueuePatch({ ...patch, headers: {...patch.headers,"x-r-changing":"1"}, defer})
        setLastPatch(patch)
    },[enqueuePatch,defer])
    const onBlur = useCallback(event => {
        const replacingPatch = event && event.replaceLastPatch && eventToPatch(event)
        setLastPatch(wasLastPatch=>{
            if(wasLastPatch) enqueuePatch(replacingPatch || wasLastPatch)
            return undefined
        })
    },[enqueuePatch])
    // x-r-changing is not the same as props.changing
    //   x-r-changing -- not blur (not final patch)
    //   props.changing -- not sync-ed with server

    // this effect is not ok: incomingValue can leave the same;
    // ? see if wasLastPatch.value in patches
    // or: send blur w/o value to sub-identity; changing = patch && "1" || props.changing
    //    useEffect(()=>{
    //        setLastPatch(wasLastPatch => wasLastPatch && wasLastPatch.value === incomingValue ? wasLastPatch : undefined)
    //    },[incomingValue])
    //
    // replacingPatch - incomingValue can be different then in lastPatch && onBlur might still be needed to signal end of input

    const patch = patches.slice(-1).map(({value})=>({value}))[0]
    const value = patch ? patch.value : incomingValue
    const changing = patch ? "1" : undefined // patch || lastPatch
    return ({value,changing,onChange,onBlur})
}
const SyncInput = memo(function SyncInput({value,onChange,...props}){
    const [patches,enqueuePatch] = useSync(identity)
    const {identity,deferSend} = onChange
    const patch = useSyncInput(patches,enqueuePatch,value,deferSend)
    return props.children({...props, ...patch})
})

/********* traverse ***********************************************************/

export const CreateNode = by => (ctx, {tp,...at}) => {
    const constr = by.tp[at.tp]
    if(at.identity) return childAt => constr ? createElement(constr,{...at,...ctx,...childAt}) : {...at,...ctx,...childAt}
    //legacy:
    const changes = Object.fromEntries(Object.keys(at).map(key=>{
        const value = at[key]
        const trans = by[key]
        const handler = trans && value && trans[value]
        return handler && [key, handler(ctx)]
    }).filter(i=>i))
    const {key} = ctx
    return childAt => {
        const children = childAt.children ?? at.content
        return changes.onChange ?
            createElement(changes.onChange.tp, {...at,at,...changes,key}, uProp=>createElement(constr||tp, uProp, children)) :
            createElement(constr||tp, {...at,at,children,...changes,key})
    }
}

/******************************************************************************/

export function VDomAttributes(){
    const onClick = ({
        "sendThen": ctx => event => { ctx.branchContext.enqueue(ctx.identity,patchFromValue("")) }
    }) //react gives some warning on stopPropagation
    const onChange = {
        "local": ctx => ({identity:ctx.identity,deferSend:changing=>true,tp:SyncInput}),
        "send": ctx => ({identity:ctx.identity,deferSend:changing=>false,tp:SyncInput}),
        "send_first": ctx => ({identity:ctx.identity,deferSend:changing=>changing,tp:SyncInput}),
    }
    const ctx = { ctx: ctx => ctx }
    const path = { "I": ctxToPath(ctx.identity) }
    const transforms = {onClick,onChange,ref,ctx,tp,path,identity}
    return ({transforms})
}
