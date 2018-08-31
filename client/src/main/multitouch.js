export default function MultiTouch(){
	let state = null
	const pinchZoom = 1
	const pinchScroll = 2
	function setHandlers(win,node,cb){
		node.addEventListener("touchstart",cb.onstart,true)
		node.addEventListener("touchmove",cb.onmove,true)
		node.addEventListener("touchend",cb.onend,true)
	}
	function removeHandlers(win,node,cb){
		node.removeEventListener("touchstart",cb.onstart,true)
		node.removeEventListener("touchmove",cb.onmove,true)
		node.removeEventListener("touchend",cb.onend,true)
	}
	function pinch(log,win,node,handleZoom){
		const tpCache = []		
		function onstart(ev) {				
			if (ev.targetTouches.length == 2) {
				ev.preventDefault()				
				tpCache.length = 0
				for(let i=0; i < ev.targetTouches.length; i++) tpCache.push(ev.targetTouches[i])							
			}
		}
		
		function onmove(ev) {		  
		  handle_pinch_zoom(ev)					  	  	
		}
		let evt
		function onend(ev) {
		  ev.preventDefault()					 	
		  if (ev.targetTouches.length == 0 && state == pinchZoom) {
			if(evt) {
				evt.preventDefault = ()=>{}
				evt.ctrlKey = true
				evt.deltaY = evt.diff>0?-100:100	
				handleZoom(evt)
			}
			evt = null
			state = null
		  }	
		}	
		function handle_pinch_zoom(ev){		
			if(!ev) return
			const cT = ev.changedTouches	
			if (tpCache.length == 2 && cT.length == 2) {	
				 ev.preventDefault()	
			   let point1=-1, point2=-1
			   tpCache.forEach((e,i)=>{
					if (e.identifier == cT[0].identifier) point1 = i
					if (e.identifier == cT[1].identifier) point2 = i
			   })			   
				if (point1 >=0 && point2 >= 0 && ev) {
					const d1 = Math.sqrt(Math.pow(tpCache[point1].clientX - tpCache[point2].clientX,2) + Math.pow(tpCache[point1].clientY - tpCache[point2].clientY,2))
					const d2 = Math.sqrt(Math.pow(cT[0].clientX - cT[1].clientX,2) + Math.pow(cT[0].clientY - cT[1].clientY,2))									
					if(Math.abs(d2-d1)<40) return
					state = pinchZoom					
					evt = {					
						clientX:tpCache[point1].clientX + (tpCache[point2].clientX - tpCache[point1].clientX)/2,
						clientY:tpCache[point1].clientY + (tpCache[point2].clientY - tpCache[point1].clientY)/2,
						diff:d2-d1
					}								
				}			
			}
		}
		setHandlers(win,node,{onstart,onmove,onend})
	}
	function scroll(log,win,node,handleDown,handleMove,handleUp){		
		function onstart(ev) {				
			if (ev.targetTouches.length == 2) {				 
				ev.preventDefault()					
				handleDown({clientX:ev.targetTouches[0].clientX,clientY:ev.targetTouches[0].clientY,preventDefault:()=>{}})
			}
		}
		function onmove(ev) {		  	 
		  const evt = handle_pinch_scroll(ev)					  	  	
		  if(evt) handleMove(evt)
		}
		
		function onend(ev) {
		  ev.preventDefault()	 
		  if (ev.targetTouches.length == 0 && state == pinchScroll) {			  
			  handleUp({clientX:ev.changedTouches[0].clientX, clientY:ev.changedTouches[0].clientY})
			  state = null	
		  }		  	 
		}	
		function handle_pinch_scroll(ev){
			if(!ev) return
			const cT = ev.changedTouches				
			if (cT.length == 2) {	
			    ev.preventDefault()
				if(!state) state = pinchScroll				
				return {					
					clientX:cT[0].clientX,
					clientY:cT[0].clientY						
				}				   		
			}
		}
		setHandlers(win,node,{onstart,onmove,onend})
	}
	return {pinch,scroll}
}