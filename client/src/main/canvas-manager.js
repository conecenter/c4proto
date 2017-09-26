
import React           from 'react'

export default function CanvasManager(canvasFactory,sender,ctxToBranchPath){

    //todo: prop.options are considered only once; do we need to rebuild canvas if they change?
    // todo branches cleanup?
    const canvasRef = prop => el => {
        const ctx = prop.ctx
        const [rCtx, branchKey] = ctxToBranchPath(ctx)
        const aliveUntil = el ? null : Date.now()+200
        const traverse = (res,node) => {
            const commands = res.concat(node.at.commands||[])
            return (node.chl||[]).reduce((res,key)=>traverse(res,node[key]), commands)
        }
        const commands = traverse([], prop.children)
        rCtx.modify(branchKey, state=>{
            const canvas = state.canvas || canvasFactory(prop.options||{})
            return ({
                ...state, canvas,
                parsed: {...prop,commands},
                checkActivate: state => {
                    if(aliveUntil && Date.now() > aliveUntil) {
                        canvas.remove()
                        return null
                    }
                    return canvas.checkActivate(state)
                },
                parentNodes: { def: el },
                sendToServer: target => sender.send(ctx, target)
            })
        })
    }

    const Canvas = prop => {
        return React.createElement("div",{ style: prop.style, ref: canvasRef(prop) },[])
    }

    const transforms = {
        tp: ({Canvas}),
        ctx: { ctx: ctx => ctx }
    };
    return ({transforms});

}
