
import {mergeAll}    from "../main/util"

export default function Branches(branchHandlers){
    const branchesByKey = {}
    function branches(data){
        const active = data.split(";").map(res=>[res[0],res.slice(1)])
        const isActive = mergeAll(active.map([k,v]=>({[k]:v})))
        Object.keys(branchesByKey).filter(k=>!isActive[k]).forEach(k=>{
            (branchesByKey[k].remove||(()=>()))()
            delete branchesByKey[k]
        })
        active.forEach([parentKey,childKeys] => childKeys.forEach(key=>{
            branchesByKey[key] = {...branchesByKey[key],
                parentBranch: ()=>branchesByKey[parentKey]
            }
        }))
    }
    const receiver = branchHandler => data => {
        const parsed = JSON.parse(data)
        const key = parsed.branchKey
        branchesByKey[key] = branchHandler(branchesByKey[key],parsed)
    }
    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map([eventName,handler] => ({[eventName]: receiver(handler)}))
            .concat({branches})
    )
    return ({receivers})
}
