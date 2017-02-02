
import {mergeAll}    from "../main/util"

export default function Branches(branchHandlers){
    const branchesByKey = {}
    const modify = (branchKey,by) => {
        branchesByKey[branchKey] =
            by(branchesByKey[branchKey] || {branchKey, modify})
        if(!branchesByKey[branchKey]) delete branchesByKey[branchKey]
    }
    //
    const remove = branchKey => modify(branchKey, state=>{
        if(state.remove) state.remove()
        return null
    })
    //const setParent = parentBranch => branchKey => modify(branchKey, state=>({...state, parentBranch}))
    const toReceiver = branchHandler => data => {
        const i = data.indexOf(" ")
        const branchKey = data.substring(0,i)
        const body = data.substring(i+1)
        modify(branchKey, branchHandler(body))
    }

    function branches(data){
        const active = data.split(";").map(res=>res.split(",")).map(res=>[res[0],res.slice(1)])
        const isActive = mergeAll(active.map([k,v]=>({[k]:v})))
        Object.keys(branchesByKey).filter(k=>!isActive[k]).forEach(remove)
        //active.forEach([parentKey,childKeys] => childKeys.forEach(setParent(()=>branchesByKey[parentKey])))
    }

    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map([eventName,handler] => ({[eventName]: toReceiver(handler)}))
            .concat({branches})
    )
    //
    function start(){ requestAnimationFrame(checkActivateAll) }
    //let frames = 0
    //setInterval(()=>{ console.log(frames); frames=0 },1000)
    function checkActivateAll(){
        requestAnimationFrame(checkActivateAll)
        Object.entries(branchesByKey).forEach(
            [k,v]=>v.checkActivate && modify(k, v.checkActivate)
        )
        //frames++
    }
    return ({receivers,start})
}
