
import {mergeAll}    from "../main/util"

export default function Branches(log,branchHandlers){
    const branchesByKey = {}
    const modify = (branchKey,by) => {
        const state = by(branchesByKey[branchKey] || {branchKey, modify})
        //if(branchesByKey[branchKey]!==state) log({a:"mod",branchKey,state})
        if(branchesByKey[branchKey]===state){}
        else if(state) branchesByKey[branchKey] = state
        else delete branchesByKey[branchKey]
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
        //log({a:"recv",branchKey})
        modify(branchKey, branchHandler(body))
    }

    function branches(data){
        const active = data.split(";").map(res=>res.split(",")).map(res=>[res[0],res.slice(1)])
        //log({a:"active",active})
        const isActive = mergeAll(active.map(([k,v])=>({[k]:v})))
        Object.keys(branchesByKey).filter(k=>!isActive[k]).forEach(remove)
        //active.forEach([parentKey,childKeys] => childKeys.forEach(setParent(()=>branchesByKey[parentKey])))
    }

    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map(([eventName,handler]) => ({[eventName]: toReceiver(handler)}))
            .concat({branches})
    )

    function checkActivate(){
        Object.entries(branchesByKey).forEach(
            ([k,v])=>v.checkActivate && modify(k, v.checkActivate)
        )
    }

    return ({receivers,checkActivate})
}
