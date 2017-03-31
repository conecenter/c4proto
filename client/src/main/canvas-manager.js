
import {splitFirst}    from "../main/util"

export default function CanvasManager(canvasFactory,feedback){

        //const {parsed,branchKey,acknowledgedSizes,parentNodes} = state
        //const fromServer = {...parsed,branchKey,acknowledgedSizes,parentNodes}



    const showCanvasData = data => state => {
        const canvas = state.canvas || canvasFactory()
        return ({
            ...state, canvas,
            remove: canvas.remove,
            checkActivate: canvas.checkActivate,
            ackChange: canvas.ackChange,
            parsed: JSON.parse(data)
        })
    }
        //acknowledgedSizes => state => ({...state, acknowledgedSizes})
    const branchHandlers = ({showCanvasData}) // todo branches cleanup
    return ({branchHandlers})
}
