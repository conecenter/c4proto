
import React           from 'react'
import ReactDOM        from 'react-dom'
import PureRenderMixin from 'react/lib/ReactComponentWithPureRenderMixin'
import update          from 'react/lib/update'

export default function VDom(log,getRootElement, createElement, activeTransforms, changes){
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

    function setupBranch(state){
        if(state.remove) return state;
        const rootNativeElement = createElement("div")
        rootNativeElement.style.overflowX = "hidden"
		rootNativeElement.style.minHeight = "100%"
        getRootElement().appendChild(rootNativeElement)
        const rootVirtualElement = React.createElement(RootComponent,null)
        const rootComponent = ReactDOM.render(rootVirtualElement, rootNativeElement)
        const remove = () => {
            rootNativeElement.parentElement.removeChild(rootNativeElement)
            ReactDOM.unmountComponentAtNode(rootNativeElement)
        }
        const ackChange = changes && changes.ackChange
        return ({...state,rootComponent,remove,ackChange})
    }

    const showDiff = data => existingState => {
        const value = JSON.parse(data)
        if(!existingState.remove && !value["$set"]) return null
        const state = setupBranch(existingState)
        const rootComponent = state.rootComponent
        const localState = {
            get(){ return rootComponent.state.local || {} },
            update(diff){
                const local = update(rootComponent.state.local || {}, diff)
                rootComponent.setState({local})
            }
        }
        const branchKey = state.branchKey
        const modify = state.modify
        const ctx = {value, localState, branchKey, modify}
        setupIncomingDiff(ctx)		
        const incoming = update(rootComponent.state.incoming || {}, ctx.value) // todo: do we need state in component?
        rootComponent.setState({incoming})
        return state
    }
    const branchHandlers = {showDiff}
    return ({branchHandlers})
}
