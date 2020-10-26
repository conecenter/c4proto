
import {spreadAll}     from "../main/util"
import {dictKeys,rootCtx,ctxToPath,chain,someKeys,ifInputsChanged} from "../main/vdom-util"

const replaceArrayTree = replace => root => {
    const traverse = arr => {
        const nArr = arr.map(el => Array.isArray(el) ? traverse(el) : replace(el))
        return arr.some((el,j)=>el!==nArr[j]) ? nArr : arr
    }
    return traverse(root)
}

const chainFromTree = (before,children,after) => root => {
    const traverse = node => chain([
        before(node), chain(children(node).map(traverse)), after(node)
    ])
    return traverse(root)
}

const bufferToArrayInner = r => r ? [...bufferToArrayInner(r.prev),r.values] : []
const bufferToArray = r => [].concat(...bufferToArrayInner(r))
const bufferAdd = values => prev => !prev || prev.values.length > values.length ?
    { prev, values } : bufferAdd(prev.values.concat(values))(prev.prev)

const addCommands = commands => someKeys({ commandsBuffer: bufferAdd(commands) })
/* w/o someKeys
const addCommands = commands => res => ({
    ...res, commandsBuffer: bufferAdd(commands)(res.commandsBuffer)
})
*/

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
    const nRes = nCommands === commands ? res : someKeys({
        colorIndex: r => r+1,
        colorToContextBuffer: bufferAdd([{ [colorKeyGen(res.colorIndex)]: ctx }])
    })(res)
/* w/o someKeys
    const nRes = nCommands === commands ? res : {
        ...res,
        colorIndex: res.colorIndex+1,
        colorToContextBuffer: bufferAdd([{ [colorKeyGen(res.colorIndex)]: ctx }])(res.colorToContextBuffer)
    }
*/
    return addCommands(nCommands)(nRes)
}

const gatherDataFromPathTree = root => chainFromTree(
    node => colorInject(node.at.commands||[], node.at.ctx),
    node => (node.chl||[]).map(key=>node[key]),
    node => addCommands(node.at.commandsFinally||[])
)(root)({ colorIndex: 0 })

export default function CanvasManager(react, canvasFactory, sender, log){
    //todo: prop.options are considered only once; do we need to rebuild canvas if they change?
    // todo branches cleanup?
    const canvasByKey = dictKeys(f=>({canvasByKey:f}))
    const canvasRef = prop => parentNode => {
        const ctx = prop.ctx
        const rCtx = rootCtx(ctx)
        const branchKey = rCtx.branchKey
        const path = ctxToPath(ctx)
        const aliveUntil = parentNode ? null : Date.now()+200
        rCtx.modify("CANVAS_UPD",canvasByKey.one(branchKey+":"+path, state => ({...state, branchKey, aliveUntil, prop, parentNode})))
    }

    const canvasStyle = prop => {
        if(prop.isGreedy || !prop.value) return prop.style;
        const [cmdUnitsPerEMZoom,aspectRatioX,aspectRatioY,pxMapH] = prop.value.split(",") // eslint-disable-line no-unused-vars
        return ({ ...prop.style, height: pxMapH+"px" })
    }

    const Canvas = prop => {
        return react.createElement("div",{ style: canvasStyle(prop), ref: canvasRef(prop) },[])
    }

    const checkIsRootBranch = (allSt,branchKey) => allSt && allSt.activeBranchesStr && allSt.activeBranchesStr.startsWith(branchKey)

    const passSyncEnabled = allSt => state => {
        const sizesSyncEnabled = checkIsRootBranch(allSt,state.branchKey)
        if(sizesSyncEnabled === state.sizesSyncEnabled) return state
        return ({...state, sizesSyncEnabled})
    }

    const setup = ifInputsChanged(log)("commandsFrom", {prop:1}, changed => state => {
        const prop = state.prop || {}
        const {commandsBuffer,colorToContextBuffer} = gatherDataFromPathTree(prop.children)
        const commands = bufferToArray(commandsBuffer)
        const colorToContext = spreadAll(...bufferToArray(colorToContextBuffer))
        const parsed = {...prop,commands}
        const sendToServer = (target,color) => sender.send(colorToContext[color], target) //?move closure
        const canvas = state.canvas || canvasFactory(prop.options||{})
        return ({...changed(state), canvas, parsed, sendToServer})
    })

    const innerActivate = state => {
        const canvas = state && state.canvas
        const checkActivate = canvas && canvas.checkActivate
        return checkActivate ? checkActivate(state) : state
    }

    const beMortal = state => {
        if(state && state.aliveUntil && Date.now() > state.aliveUntil) {
            state.canvas.remove()
            return null
        } else return state
    }

    const checkActivate = modify => modify("CANVAS_FRAME",allSt=>canvasByKey.all(
        state=>beMortal(innerActivate(setup(passSyncEnabled(allSt)(state))))
    )(allSt))

    const transforms = { tp: ({Canvas}) };
    return ({transforms,checkActivate});

}
