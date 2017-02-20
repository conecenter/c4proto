
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
    function setupIncomingDiff(ctx,modify) {
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

    const send = (ctx, action, value) => state => state.addSend("/connection", { // todo: may be we need a queue to be sure server will receive messages in right order
        "X-r-action": action,
        "X-r-vdom-value-base64": encode(value),
        "X-r-branch": rootCtx(ctx).branchKey,
        "X-r-vdom-path": ctxToPath(ctx)
    })

    //todo .send(...)(state); no .modify; no localState

    const showDiff = (branchKey,data) => state => {
        const value = JSON.parse(data)
        //state.updateBranch(branchKey,{local})

        const ctx = {value, branchKey, send}
        setupIncomingDiff(ctx,state.modify)
        const incoming = update(state.branches[branchKey].incoming || {}, ctx.value)
        state.updateBranch(branchKey,{incoming})
        return state
    }

    const init = state => {
        if(state.rootNativeElement) return state
        const rootNativeElement = createElement("div")
        getRootElement().appendChild(rootNativeElement)
        return ({...state, rootNativeElement})
    }
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