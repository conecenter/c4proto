
// mostly functional

import {chain,mergeAll,lensProp,branchProp,branchesActiveProp,branchSend} from "../main/util"

import React           from 'react'
import ReactDOM        from 'react-dom'
import PureRenderMixin from 'react/lib/ReactComponentWithPureRenderMixin'
import update          from 'react/lib/update'

export default function VDom({getRootElement, createElement, activeTransforms, encode}){
    function never(){ throw ["traverse error"] }
    const Traverse = React.createClass({
        mixins: [PureRenderMixin],
        render(){
            const incoming = this.props.incoming || never()
            const local = this.props.local || {}
            const at = local.at && incoming.at ? {...incoming.at, ...local.at} : local.at || incoming.at || never()
            const content =
                incoming.chl ? incoming.chl.map(
                    key => React.createElement(Traverse, {key, incoming:incoming[key], local:local[key]})
                ) :
                at.content || null
            return React.createElement(at.tp, at, content)
        }
    })
    const setupIncomingDiff = (rValue,ctx) => state => {
        const visit = (pValue,ctx) => mergeAll(Object.entries(pValue).map(([key,value])=>{
            const trans = activeTransforms[key]
            const handler = trans && value && (trans[value] || trans[value[0]])
            const out = handler && key==="tp" ? handler :
                handler ? state.toListener(handler({ value, parent: ctx })) :
                key.substring(0,1)===":" || key === "at" ? visit(value, { key, parent: ctx }) :
                key === "$set" ? visit(value, ctx) : value
            return ({[key]: out})
        }))
        return visit(rValue,ctx)
    }

    const incomingProp = lensProp("incoming")

    const showDiff = (branchKey,data) => state => {
        const diff = setupIncomingDiff(JSON.parse(data), {branchKey})(state)
        const branchIncomingProp = branchProp(branchKey).compose(incomingProp)
        return branchIncomingProp.modify(was=>update(was||{},diff))(state)
    }

    const createRoot = () => {
        const el = createElement("div")
        getRootElement().appendChild(el)
        return el
    }
    const rootNativeElementProp = lensProp("rootNativeElement")
    const init = rootNativeElementProp.modify(v => v || createRoot())
    const render = state => {
        const items = (branchesActiveProp.of(state)||[]).map(key=>{
            const branch = branchProp(key).of(state)
            return branch.incoming && React.createElement(Traverse,{...branch,key})
        }).filter(v=>v)
        ReactDOM.render(React.createElement("div",{},items), rootNativeElementProp.of(state))
        return state
    }
    const checkActivate = chain([init,render])
    const branchHandlers = {showDiff}
    return ({branchHandlers,checkActivate})
}

/*
const remove = () => {
            rootNativeElement.parentElement.removeChild(rootNativeElement)
            ReactDOM.unmountComponentAtNode(rootNativeElement)
        }*/