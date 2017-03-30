
import {splitFirst}    from "../main/util"

export default function CanvasManager(canvasFactory,feedback){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}



    const showCanvasData = data => state => {
        const [acknowledgedSizes,body] = splitFirst(" ", data)
        const canvas = state.canvas || canvasFactory()
        return ({
            ...state, canvas, acknowledgedSizes,
            remove: canvas.remove, checkActivate: canvas.checkActivate,
            parsed: JSON.parse(body)
        })
    }

    const ackChange = data => state => ({...state, acknowledgedIndex: parseInt(data)})
        //acknowledgedSizes => state => ({...state, acknowledgedSizes})
    const branchHandlers = ({showCanvasData,ackChange}) // todo branches cleanup
    return ({branchHandlers})
}
