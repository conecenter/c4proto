
import React           from 'react'

export default function CanvasManager(canvasFactory,sender,ctxToBranchPath){

    //todo: prop.options are considered only once; do we need to rebuild canvas if they change?
    // todo branches cleanup?
	const colorKeyMarker = "[colorPH]"
	const colorKeyGen = (fromN) =>{
		const color = (fromN,pos)=>{
			const size = 5
			const mask = (1<<size) - 1
			return ((fromN>>(size*pos)) & mask) << 3
		}
		return `rgb(${color(fromN,2)},${color(fromN,1)},${color(fromN,0)})`
	}
	const findReplaceColorMarker = (commandArray,getColor)=>{
		commandArray.forEach((c,i)=>{
			if(Array.isArray(c)) findReplaceColorMarker(c,getColor)
			else if(c==colorKeyMarker) commandArray[i] = getColor()
		})
	}
    const canvasRef = prop => el => {
        const ctx = prop.ctx
        const [rCtx, branchKey] = ctxToBranchPath(ctx)
        const aliveUntil = el ? null : Date.now()+200
		let colorIndex = 0
		const colorInject = (node) =>{
			let replaced = false
			const commandsWithEvColor = (node.at.commands||[]).slice()
			findReplaceColorMarker(commandsWithEvColor,()=>{replaced=true; return colorKeyGen(colorIndex)})
			if(replaced) colorIndex++
			return commandsWithEvColor
		}
		
        const traverse = (res,node) => {			
            const commands = res.concat(colorInject(node))            
            return (node.chl||[]).reduce((res,key)=>traverse(res,node[key]), commands)
        }
		const colorAtCtx = (node,color) =>{
			const commands = node.at.commands||[]
			if(commands.some((c,i)=>c=="over" && commands[i-1].includes(color))) return node.at.ctx			
			return (node.chl||[]).reduce((res,key)=>(res?res:colorAtCtx(node[key],color)),null)
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
                sendToServer: (target,color) => sender.send(colorAtCtx(prop.children,color), target)				
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
