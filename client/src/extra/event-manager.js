
const eventManager = (()=>{
	let w
	const checkForEvent = (event) => typeof event === "function"	
	const getParentW = (w) => {
		let _w = w
		while(_w && _w.parent != _w) _w = _w.parent
		return _w
	}
	const create = el=>(type,params) => {
		const doc =  (el.ownerDocument || el.documentElement.ownerDocument)
		w = getParentW(doc.defaultView)
		switch(type){
			case "keydown": if(checkForEvent(w.KeyboardEvent)) return (new w.KeyboardEvent(type,params)); break				
			case "click": if(checkForEvent(w.MouseEvent)) return (new w.MouseEvent(type,params)); break
			case "mousedown": if(checkForEvent(w.MouseEvent)) return (new w.MouseEvent(type,params)); break			
		}						
		const _params = params && (params.code =="vk" && type =="keydown")?{...params,detail:params}:params //ie11 hack
		return (new w.CustomEvent(type,_params))
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