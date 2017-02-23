
import {branchesActiveProp,branchProp,chain} from "../main/util"

export default function CanvasManager(canvasFactory){

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
        (branchesActiveProp.of(state)||[]).map(branchKey=>{
            const branch = branchProp(branchKey)(state)
            return branch && branch.canvas && branch.canvas.checkActivate(branchKey)
        })
    )(state)

    const branchHandlers = ({showCanvasData,ackCanvasResize}) // todo branch cleanup
    return ({branchHandlers,checkActivate})
}
