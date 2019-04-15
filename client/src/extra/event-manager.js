
const eventManager = (()=>{
	let w
	const create = el=>(type,params) => {
	w = el.ownerDocument.defaultView
		switch(type){
			case "keydown": return (new w.KeyboardEvent(type,params))
			case "click": return (new w.MouseEvent(type,params))
			case "mousedown": return (new w.MouseEvent(type,params))
			default: return (new w.CustomEvent(type,params))
		}
	}	
	const sendToWindow = (event)=> w.dispatchEvent(event)
	return {create,sendToWindow}
})()

const checkActivateCalls=(()=>{
	const callbacks=[]
	const add = (c) => callbacks.push(c)
	const remove = (c) => {
		const index = callbacks.indexOf(c)
		if(index>=0) callbacks.splice(index,1)
	}
	const check = (modify) => callbacks.forEach(c=>c(modify))	
	return {add,remove,check}
})()

export {eventManager,checkActivateCalls}