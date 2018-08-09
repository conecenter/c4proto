export default function MultiTouch(log,node,callback){
	const tpCache = []	
	function setHandlers(node){
		node.addEventListener("touchstart",onstart)
		node.addEventListener("touchmove",onmove)
		node.addEventListener("touchend",onend)
	}
	function removeHandlers(node){
		node.removeEventListener("touchstart",onstart)
		node.removeEventListener("touchmove",onmove)
		node.removeEventListener("touchend",onend)
	}
	function onstart(ev) {	
		ev.preventDefault()		
		if (ev.targetTouches.length == 2) {for(let i=0; i < ev.targetTouches.length; i++) {
			tpCache.push(ev.targetTouches[i])			
		}
			log(tpCache)
		}
	}
	function onmove(ev) {
	  ev.preventDefault()	 
	  handle_pinch_zoom(ev)					  	  	
	}
	let evt = {}
	function onend(ev) {
	  ev.preventDefault()	 
	  if (ev.targetTouches.length == 0) {
		  callback(evt)					  
		   evt = {}
	  }
	  tpCache.length = 0	 
	}	
	function handle_pinch_zoom(ev){		
		if(!ev) return
		const cT = ev.changedTouches	
		if (tpCache.length == 2 && cT.length == 2) {		   
		   let point1=-1, point2=-1
		   tpCache.forEach((e,i)=>{
				if (e.identifier == cT[0].identifier) point1 = i
				if (e.identifier == cT[1].identifier) point2 = i
		   })			   
			if (point1 >=0 && point2 >= 0 && ev) {
				const d1 = Math.sqrt(Math.pow(tpCache[point1].clientX - tpCache[point2].clientX,2) + Math.pow(tpCache[point1].clientY - tpCache[point2].clientY,2))
				const d2 = Math.sqrt(Math.pow(cT[0].clientX - cT[1].clientX,2) + Math.pow(cT[0].clientY - cT[1].clientY,2))				
				if(Math.abs(d2-d1)<10) return
				evt = {					
					clientX:tpCache[point1].clientX + (tpCache[point2].clientX - tpCache[point1].clientX)/2,
					clientY:tpCache[point1].clientY + (tpCache[point2].clientY - tpCache[point1].clientY)/2,
					diff:d2-d1
				}								
			}			
		}
	}
	setHandlers(node)
}