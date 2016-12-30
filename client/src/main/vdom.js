
import React           from 'react'
import ReactDOM        from 'react-dom'
import PureRenderMixin from 'react/lib/ReactComponentWithPureRenderMixin'
import update          from 'react/lib/update'

export default function VDom(parentElement){
    const activeTransforms = {}
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
    function showDiff(data){
        const diff = JSON.parse(data)
        setupIncomingDiff({ value: diff })
        const incoming = update(rootComponent.state.incoming || {}, diff)
        rootComponent.setState({incoming})
    }
    
    const rootNativeElement = document.createElement("div")
    parentElement.appendChild(rootNativeElement)
    const rootVirtualElement = React.createElement(RootComponent,null)
    const rootComponent = ReactDOM.render(rootVirtualElement, rootNativeElement)
    const localState = {
        get(){ return rootComponent.state.local || {} },
        update(diff){
            const local = update(rootComponent.state.local || {}, diff)
            rootComponent.setState({local}) 
        }
    }
    function transformBy(add){ merge(activeTransforms, add.transforms) }
    const receivers = {showDiff}
    return ({receivers,localState,ctxToArray,transformBy})
}

function merge(to, from){
    Object.keys(from).forEach(key=>{
        if(!to[key]) to[key] = from[key] 
        else if(to[key].constructor===Object && from[key].constructor===Object) 
            merge(to[key],from[key])
        else never()
    })
}

function ctxToArray(ctx,res){ 
    if(ctx){
        ctxToArray(ctx.parent, res)
        if(ctx.key) res.push(ctx.key)
    }
    return res
}
