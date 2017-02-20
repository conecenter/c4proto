
import React           from 'react'
import ReactDOM        from 'react-dom'
import PureRenderMixin from 'react/lib/ReactComponentWithPureRenderMixin'
import update          from 'react/lib/update'

export default function VDom({getRootElement, createElement, activeTransforms, encode, rootCtx, ctxToPath}){
    function never(){ throw ["traverse error"] }
    const Traverse = React.createClass({
        mixins: [PureRenderMixin],
        render(){
            const incoming = this.props.incoming || never()
            const local = this.props.local || {}
            const at = local.at && incoming.at ? Object.assign({}, incoming.at, local.at) : local.at || incoming.at || never() 
            const content =
                incoming.chl ? incoming.chl.map(
                    key => React.createElement(Traverse, {key, incoming:incoming[key], local:local[key]})
                ) :
                at.content || null
            return React.createElement(at.tp, at, content)
        }
    })
    const RootComponent = React.createClass({
        mixins: [PureRenderMixin],
        getInitialState(){ return ({}) },
        render(){ 
            return this.state.incoming ? 
                React.createElement(Traverse,{incoming: this.state.incoming, local: this.state.local }) : 
                React.createElement("div",null)
        }
    })

    const toListener = modify => transform => ev => modify(transform(ev))
    const setupIncomingDiff = (ctx,toListener) => {
        const visit = ctx => mergeAll(Object.entries(ctx.value).map(([key,value])=>{
            const trans = activeTransforms[key]
            const handler = trans && value && (trans[value] || trans[value[0]])
            out = handler ? (key==="tp" ? handler : toListener(handler({ value, parent: ctx }))) :
                key.substring(0,1)===":" || key === "at" ? visit({ key, value, parent: ctx }) :
                key === "$set" ? visit({ value, parent: ctx }) : value
            return ({[key]: out})
        }))
        return visit(ctx)
    }










    const send = (ctx, action, value) => state => state.addSend("/connection", { // todo: may be we need a queue to be sure server will receive messages in right order
        "X-r-action": action,
        "X-r-vdom-value-base64": encode(value),
        "X-r-branch": rootCtx(ctx).branchKey,
        "X-r-vdom-path": ctxToPath(ctx)
    })

    //todo: no .modify; no localState; .branchKey not in root; all transforms to real

    const showDiff = (branchKey,data) => state => {
        const value = JSON.parse(data)
        const ctx = {value, branchKey, send}
        const diff = setupIncomingDiff(ctx,toListener(state.modify))
        return transformNested("branches",transformNested(branchKey,transformNested("incoming",
            was=>update(was||{},diff)
        )))(state)
    }

    const createRoot = () => {
        const el = createElement("div")
        getRootElement().appendChild(el)
        return el
    }

    const init = state => transformNested("rootNativeElement",v=> v || createRoot())

    const render = state => {
        ReactDOM.render(React.createElement(Root,state), state.rootNativeElement)
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