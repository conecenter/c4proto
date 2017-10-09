
/*
import React           from 'react'
import ReactDOM        from 'react-dom'
import PureRenderMixin from 'react/lib/ReactComponentWithPureRenderMixin'

function getSameCount(a,b){
    const cmp = i => i < a.length && i < b.length && a[i] === b[i] ? cmp(i+1) : i
    return cmp(0)
}

function last(a){ return a[a.length-1] }

export default function VDomRender(){
    const merge = merger((l,r)=>r)
    const reduceMerge = (res,patch) => [...res, merge(last(res), patch)]
    const checkActivate = (was,branchesByKey) => {
        const patches = Object.values(branchesByKey).map(b=>b.vDomPatch)
            .filter(i=>i).sort((a,b)=>a.priority-b.priority).map(i=>i.value)
        const sameCount = getSameCount(patches, was.patches)
        if(sameCount === patches.length && sameCount === was.patches.length) return
        const sameResults = was.results.slice(0,sameCount)
        const results = patches.slice(sameCount).reduce(reduceMerge, sameResults)
        const rootNativeElement = was.rootNativeElement || createRootNativeElement()
        const rootVirtualElement = React.createElement(Traverse,last(results))
        ReactDOM.render(rootVirtualElement, rootNativeElement)
        return [{...was,patches,results,rootNativeElement}]
    }
    const createRootNativeElement = () => {
        const rootNativeElement = createElement("div")
        getRootElement().appendChild(rootNativeElement)
        return rootNativeElement
    }
    const Traverse = React.createClass({
        mixins: [PureRenderMixin],
        render(){
            const at = this.props.at
            const chl = this.props.chl
            const content =
                chl ? chl.map(key => React.createElement(Traverse, {...at[key],key})) :
                at.content || null
            return React.createElement(at.tp, at, content)
        }
    })
    const receiveEvents = ["branches","ackChange","showDiff"]

    return ({checkActivate,receiveEvents})
}
*/


/*
function apply(patch,base){
    if(!patch) return base
    const result = base ? merge(base.result, patch.patchValue) : patch.patchValue
    return ({...patch,result,base})
}
function replace(was,key,add){
    if(!was) return apply(add,null)
    const [addNow,addLater] = add.level < was.level ? [null,add]:[add,null]
    const base = replace(was.base,key,addLater)
    const will = was.key === key ? base :
        base === was.base ? was : apply(was.patch, base)
    return apply(addNow,will)
}*/

/*
import update from 'react/lib/update'

export default function VDom(log,getRootElement, createElement, activeTransforms, changes){
    function setupIncomingDiff(ctx) {
        Object.keys(ctx.value).forEach(key => {
            const value = ctx.value[key]
            const trans = activeTransforms[key]
            const handler = trans && value && (trans[value] || trans[value[0]])
            if(handler) {
                ctx.value[key] = key==="tp" ? handler : handler({ value, parent: ctx })
            }
            else if(key.substring(0,1)===":" || key === "at") setupIncomingDiff({ key, value, parent: ctx })
            else if(key === "$set") setupIncomingDiff({ value, parent: ctx })
        })
    }
    const showDiff = data => state => {
        const value = JSON.parse(data)
        if(!state.remove && !value["$set"]) return null
        const branchKey = state.branchKey
        const ctx = { value: {[branchKey]:value}, modify: state.modify }
        setupIncomingDiff(ctx)
        const incoming = update(state.incoming || {[branchKey]:{}}, ctx.value)
        const vDomPatch = { value: incoming, priority: 1000000+Data.now()}
        const remove = () => {}
        const ackChange = changes && changes.ackChange
        return {...state,incoming,remove,ackChange,vDomPatch}
    }
    const branchHandlers = {showDiff}
    return ({branchHandlers})
}
*/


/*
import {mergeAll,splitFirst}    from "../main/util"

export default function Branches(log,branchHandler){
    const branchesByKey = {}
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

    const receivers = branchHandler.receiveEvents.reduce((res,eventName)=>({
        ...res, [eventName]: data => branchHandler[eventName](data,modify)
    }))

    let actors = [branchHandler]
    function checkActivate(){
        Object.keys(branchesByKey).forEach(k => modify(k, v => (v.checkActivate || (v=>v))(v) ))
        actors = actors.reduce((res,actor)=>{
            return res.concat(actor.checkActivate(actor,branchesByKey)||[actor])
        })
    }

    return ({receivers,checkActivate})
}

*/