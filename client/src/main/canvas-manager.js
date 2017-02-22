
import {branchesProp,branchProp} from "../main/util"

export default function CanvasManager(canvasFactory,chain){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}

    const showCanvasData = (branchKey,data) => branchProp(branchKey).modify(was=>({
        parsed: JSON.parse(data),
        canvas: was.canvas || canvasFactory()
    }))

    const ackCanvasResize = (branchKey,acknowledgedSizes) => branchProp(branchKey).modify(was=>({
        ...was, acknowledgedSizes
    }))

    const checkActivate = state => chain(
        Object.entries(branchesProp.of(state)||{}).filter(([k,v])=>v.canvas).map(([k,v])=>v.canvas.checkActivate(k))
    )(state)

    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branch cleanup
    return ({branchHandlers,checkActivate})
}
