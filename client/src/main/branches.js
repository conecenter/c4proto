
import {mergeAll,splitFirst}    from "../main/util"

export default function Branches(log,branchHandlers){
    const branchesByKey = {}
    let active = []
    let toModify = []
    const doModify = ({branchKey,by}) => {
        const state = by(branchesByKey[branchKey] || {branchKey, modify})
        //if(branchesByKey[branchKey]!==state) log({a:"mod",branchKey,state})
        if(branchesByKey[branchKey]!==state){
            log({state,branchKey})
            if(state) branchesByKey[branchKey] = state
            else delete branchesByKey[branchKey]
        }
    }

    const modify = (branchKey,by) => {
        toModify = [...toModify,{branchKey,by}]
        if(toModify.length > 1) return
        while(toModify.length > 0){
            doModify(toModify[0])
            toModify = toModify.slice(1)
        }
    }
    //
    const remove = branchKey => modify(branchKey, state=>{
        if(state.remove
)
    state.remove()
    return null
    })
    //const setParent = parentBranch => branchKey => modify(branchKey, state=>({...state, parentBranch}))
    const toReceiver = branchHandler => data => {
        const [branchKey,body] = splitFirst(" ", data)
        log({a:"recv",branchKey,body})
        modify(branchKey, branchHandler(body))
    }

    function branches(data){
        active = data.split(";").map(res = > res.split(",")[0]
    )  //.map(res=>res.split(",")).map(res=>[res[0],res.slice(1)])
        log({a:"active",active,data})
        const isActive = mergeAll(active.map(k=>({[k]:1})))
        Object.keys(branchesByKey).filter(k=>!isActive[k]).forEach(remove)
        //active.forEach([parentKey,childKeys] => childKeys.forEach(setParent(()=>branchesByKey[parentKey])))
    }

    function ackChange(data){
        const [branchKey,body] = splitFirst(" ", data)
        modify(branchKey, v => (v.ackChange || (data=>v=>v))(body)(v) )
    }

    const receivers = mergeAll(
        Object.entries(branchHandlers)
            .map(([eventName,handler]) => ({[eventName]: toReceiver(handler)}))
            .concat({branches,ackChange})
    )

    function checkActivate(){
        const [skipRoot, ...branchKeys] = active
        branchKeys.forEach(k = > modify(k, v = > (v.checkActivate || (v = > v)
    )
        (v)
    ))
    }

    return ({receivers,checkActivate})
}
