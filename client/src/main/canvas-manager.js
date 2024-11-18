// @ ts-check
import {spreadAll,weakCache}     from "../main/util"

const chain = functions => arg => functions.reduce((res,f)=>f(res), arg)
const deleted = ks => st => spreadAll(Object.keys(st).filter(ck=>!ks[ck]).map(ck=>({[ck]:st[ck]})))

const oneKey = (k,by) => st => {
    const was = st && st[k]
    const will = by(was)
    return was === will ? st : will ? {...(st||{}), [k]: will} : !st ? st : deleted({[k]:1})(st)
}
const someKeys = bys => chain(Object.keys(bys).map(k=>oneKey(k,bys[k])))

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

const gatherDataFromPathTree = weakCache(root => {
    const {commandsBuffer,colorToContextBuffer} = chainFromTree(
        node => colorInject(node.at.commands||[], node.at.ctx),
        node => (node["@children"]||[]).map(key=>node[key]),
        node => addCommands(node.at.commandsFinally||[])
    )(root)({ colorIndex: 0 })
    const commands = bufferToArray(commandsBuffer)
    const colorToContext = spreadAll(bufferToArray(colorToContextBuffer))
    return [commands,colorToContext]
})

////

import {createElement,useEffect,createContext,useContext,useMemo,useState} from "./hooks"
import {manageAnimationFrame} from "./util"

export const CanvasContext = createContext({sizesSyncEnabled:false/*isRoot*/, canvasFactory: null, send: null})

const getHeight = value => {
    const [cmdUnitsPerEMZoom,aspectRatioX,aspectRatioY,pxMapH] = value.split(",") // eslint-disable-line no-unused-vars
    return pxMapH
}
export const Canvas = prop => {
    const {isGreedy, value, style: argStyle, options} = prop
    const [parentNode, ref] = useState()
    const {sizesSyncEnabled, canvasFactory, send} = useContext(CanvasContext)
    const canvas = useMemo(()=>canvasFactory(options||{}), [canvasFactory, options])
    useEffect(()=>{
        const [commands,colorToContext] = gatherDataFromPathTree(prop.children)/*raw*/
        const parsed = {...prop,commands}
        const sendToServer = (target,color) => send(colorToContext[color], target) //?move closure
        const state = {parentNode,sizesSyncEnabled,canvas,parsed,sendToServer}
        return parentNode && canvas && manageAnimationFrame(parentNode, ()=>canvas.checkActivate(state))
    })
    useEffect(()=>()=>{ canvas?.remove() }, [canvas])
    const style = isGreedy || !value ? argStyle : {...argStyle, height: getHeight(value)+"px"}
    return createElement("div",{ style, ref },[])
}
