
import React           from 'react'

const replaceArrayTree = replace => root => {
    const traverse = arr => {
        const nArr = arr.map(el => Array.isArray(el) ? traverse(el) : replace(el))
        return arr.some((el,j)=>el!==nArr[j]) ? nArr : arr
    }
    return traverse(root)
}

const chain = functions => arg => functions.reduce((res,f)=>f(res), arg)

const chainFromTree = (before,children,after) => root => {
    const traverse = node => chain([
        before(node), chain(children(node).map(traverse)), after(node)
    ])
    return traverse(root)
}

///

const addCommands = commands => res => ({
    ...res, commands: res.commands.concat(commands)
})

const color = (fromN,pos)=>{
    const size = 5
    const mask = (1<<size) - 1
    return ((fromN>>(size*pos)) & mask) << 3
}

const colorKeyGen = (fromN) =>{
    return `rgb(${color(fromN,2)},${color(fromN,1)},${color(fromN,0)})`
}
const colorKeyMarker = "[colorPH]"
const colorInject = (commands,ctx) => res => {
    const nCommands = replaceArrayTree(
        el => el===colorKeyMarker ? colorKeyGen(res.colorIndex) : el
    )(commands)
    const nRes = nCommands === commands ? res : {
        ...res,
        colorIndex: res.colorIndex+1,
        colorToContext: { ...res.colorToContext, [colorKeyGen(res.colorIndex)]: ctx }
    }
    return addCommands(nCommands)(nRes)
}

const gatherDataFromPathTree = root => chainFromTree(
    node => colorInject(node.at.commands||[], node.at.ctx),
    node => (node.chl||[]).map(key=>node[key]),
    node => addCommands(node.at.commandsFinally||[])
)(root)({ colorIndex: 0, colorToContext: {}, commands: [] })

export default function CanvasManager(canvasFactory,sender,ctxToBranchPath){
    //todo: prop.options are considered only once; do we need to rebuild canvas if they change?
    // todo branches cleanup?
    const canvasRef = prop => el => {
        const ctx = prop.ctx
        const [rCtx, branchKey] = ctxToBranchPath(ctx)
        const aliveUntil = el ? null : Date.now()+200
        const {commands,colorToContext} = gatherDataFromPathTree(prop.children)
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
                sendToServer: (target,color) => sender.send(colorToContext[color], target)
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
