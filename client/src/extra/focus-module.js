export default function FocusModule({log,documentManager,eventManager,windowManager,miscReact}){		
	let nodesObj = [];
	let currentFocusNode = null;
	let preferNode = null;
	const callbacks = []
	const {addEventListener,setTimeout} = windowManager
	const {isReactRoot,getReactRoot} = miscReact	
	const distance = (no1,no2) =>{
		if(!no1 || !no2) return undefined
		const a = (no2.fy - no1.fy)
		const b = (no2.fx - no1.fx)
		return Math.sqrt(a*a + b*b)
	}
	const axisDef = (angle,axis) => {		
		switch(axis){
			case 0: return angle>=-45 && angle<=45
			case 1: return angle>=45 && angle<=135
			case 2:	return angle>=135 || angle<=-135
			case 3: return angle>=-135 && angle<=-45
		}		
	}
	const calcAngle = (m,mc) => m&&(Math.atan2(m.fy - mc.fy, m.fx - mc.fx) * 180 / Math.PI)
	const isNeightbor = (axis,angle,i,m,mc) =>{
		if(angle != 90 && angle != 0) return false
		if(axis == 0 && i==0 && m.fx - mc.fx >= 0) return true
		if(axis == 1 && i==3 && m.fy - mc.fy >= 0) return true
		if(axis == 2 && i==1 && m.fx - mc.fx <= 0) return true
		if(axis == 3 && i==2 && m.fy - mc.fy <= 0) return true
		return false
	}
	const findBestDistance = (axis) => {
		const aEl = documentManager.activeElement()
		if((axis==2||axis==0) && aEl.tagName == "INPUT") return
		const index = nodesObj.findIndex(o => o.n == currentFocusNode)
		const cNodeObj = nodesObj[index]
		const k = [-1,1]
		const bestDistance = nodesObj.reduce((a,o,i) =>{
			if(o!=cNodeObj){
				const m0 = {fy:(o.y1+o.y0)/2,fx:(o.x1+o.x0)/2} //center
				const m1 = {fy:(o.y1+o.y0)/2,fx:o.x0} //left
				const m2 = {fy:(o.y1+o.y0)/2,fx:o.x1} //right
				const m3 = {fy:o.y1,fx:(o.x1+o.x0)/2} //down
				const m4 = {fy:o.y0,fx:(o.x1+o.x0)/2} //up
				const mc0 = {fy:(cNodeObj.y1+cNodeObj.y0)/2,fx:cNodeObj.x1}
				const mc1 = {fy:cNodeObj.y1,fx:(cNodeObj.x1+cNodeObj.x0)/2}
				const mc2 = {fy:(cNodeObj.y1+cNodeObj.y0)/2,fx:cNodeObj.x0}
				const mc3 = {fy:cNodeObj.y0,fx:(cNodeObj.x1+cNodeObj.x0)/2}
				let mc = null
				let m = null
				if(axis == 0) {
					mc = mc0 //right
					m = m1
				}
				if(axis == 1) {
					mc = mc1 //down
					m = m4
				}
				if(axis == 2) {
					mc = mc2 //left
					m = m2
				}
				if(axis == 3) {
					mc = mc3 //up				
					m = m3
				}				
				const hitLine0 = k.reduce((_,e)=>{
					if(o.x0 <= mc.fx - e*(mc.fy - o.y0) && mc.fx - e*(mc.fy - o.y0) <= o.x1)
						return {fx:mc.fx - e*(mc.fy - o.y0),fy:o.y0}
					else 
						return _
				},null)
				const hitLine1 = k.reduce((_,e)=>{
					if(o.x0 <= mc.fx - e*(mc.fy - o.y1) && mc.fx - e*(mc.fy - o.y1) <= o.x1)
						return {fx:mc.fx - e*(mc.fy - o.y1),fy:o.y1}
					else 
						return _
				},null)
				const hitLine2 = k.reduce((_,e)=>{
					if(o.y0 <= e*(o.x0 - mc.fx) + mc.fy && e*(o.x0 - mc.fx) + mc.fy <= o.y1)
						return {fx:o.x0,fy:e*(o.x0 - mc.fx) + mc.fy}
					else 
						return _
				},null)
				const hitLine3 = k.reduce((_,e)=>{
					if(o.y0 <= e*(o.x1 - mc.fx) + mc.fy && e*(o.x1 - mc.fx) + mc.fy <= o.y1)
						return {fx:o.x0,fy:e*(o.x1 - mc.fx) + mc.fy}
					else 
						return _
				},null)
				
				const hitLine = [hitLine0,hitLine1,hitLine2,hitLine3].find(h=>h!=null)				
				const Ms = [m1,m2,m3,m4,m0,hitLine]
				const Ds = Ms.map(m=>()=>distance(m,mc))
				const Angles = Ms.map(m=>calcAngle(m,mc))											
				const d = Angles.reduce((_,e,i)=>{						
					if(axisDef(e,axis) || isNeightbor(axis,e,i,m,mc)) {
						const d = Ds[i]()
						if(_ == null || _ > d) 
							return d
					}
					return _
				},null)					
				if(d != null && (!a || a.d>d)) 
					return {o,d}					
			}						
			return a
		},null)			
		return bestDistance
	}
	const sendEvent = (event) => {
		if(!currentFocusNode) return;
		const controlEl = currentFocusNode.querySelector("input,button,.button")		
		const innerTab = currentFocusNode.querySelector('[tabindex="1"]')
		const cEvent = event()
		if(controlEl) 
			controlEl.dispatchEvent(cEvent)
		else if(innerTab)				
			innerTab.dispatchEvent(cEvent)
		else
			currentFocusNode.dispatchEvent(cEvent)
	}
	const onKeyDown = (event) =>{
		if(nodesObj.length == 0) return
		let best = null	
		switch(event.key){
			case "ArrowUp":
				best = findBestDistance(3);break;
			case "ArrowDown":
				best = findBestDistance(1);break;
			case "ArrowLeft":
				best = findBestDistance(2);break;
			case "ArrowRight":
				best = findBestDistance(0);break;
			case "Escape":
				currentFocusNode.focus();break;
			case "Tab":				 
				currentFocusNode.focus();
				onTab(event)
				event.preventDefault();return;
			case "F2":	
			case "Enter":
				sendEvent(()=>eventManager.create("enter"));break;					
			case "Delete":
			case "Backspace":
				sendEvent(()=>eventManager.create("delete"));break;			
			case "Insert":
			case "c":
				if(event.ctrlKey){
					sendEvent(()=>eventManager.create("ccopy"))
				}
				break;					
		}		
		if(best) best.o.n.focus();				
		if(isPrintableKeyCode(event.keyCode)) {
			sendEvent(()=>eventManager.create("delete",{detail:event.key}))
			const cRNode = callbacks.find(o=>o.el == currentFocusNode)
			if(cRNode.props.sendKeys) sendToServer(cRNode,"key",event.key)
		}			
	}
	const sendToServer = (cRNode,type,action) => {if(cRNode.props.onClickValue) cRNode.props.onClickValue(type,action)}
	const onTab = (event) =>{
		const root = getReactRoot();
		if(!root) return
		const nodes = Array.from(root.querySelectorAll('[tabindex="1"]'))		
		const cRNode = callbacks.find(o=>o.el == currentFocusNode)
		if(cRNode.props.autoFocus == false){
			sendToServer(cRNode,"focus","change")
			return
		}
		
		const cIndex = nodes.findIndex(n=>n == currentFocusNode)
		if(cIndex>=0) {
			if(cIndex+1<nodes.length) nodes[cIndex+1].focus()
			else{
				setTimeout(()=>{
					const nodes = Array.from(root.querySelectorAll('[tabindex="1"]'))		
					const cIndex = nodes.findIndex(n=>n == currentFocusNode)
					if(cIndex>=0) {
						if(cIndex+1<nodes.length) nodes[cIndex+1].focus()
						else currentFocusNode.focus()
					}					
				},200)
			}				
		}		
		
	}
	const onPaste = (event) => {
		const data = event.clipboardData.getData("text")
		sendEvent(()=>eventManager.create("cpaste",{detail:data}))
	}		
	addEventListener("keydown",onKeyDown)
	addEventListener("paste",onPaste)
	addEventListener("cTab",onTab)
	const isPrintableKeyCode = 	(kc) => (kc == 32 || (kc >= 48 && kc <= 57) || 
								(kc >= 65 && kc <= 90) || (kc >= 186 && kc <= 192) || 
								(kc >= 219 && kc <= 222) || kc == 226 || kc == 110 || 
								(kc >= 96 && kc <= 105) || kc == 106 || kc == 107 || kc == 109)

	const doCheck = () => {
		const root = getReactRoot();
		if(!root) return
		const nodes = /*Array.from(root.querySelectorAll('[tabindex="1"]'))*/callbacks.map(o=>o.el)
		if(nodes.length==0) return
		const newNodesObj = nodes.map(n=>{
			const r = n.getBoundingClientRect()				
			return {y0:r.top,x0:r.left,y1:r.bottom,x1:r.right,n}
		})
		if(nodesObj.length!=newNodesObj.length || nodesObj.some((o,i)=>o.n!=newNodesObj[i].n)) {
			nodesObj = newNodesObj			
			if(!nodesObj.find(o=>o.n == currentFocusNode)) currentFocusNode = null
		}
		if(!currentFocusNode && nodesObj.length>0) {currentFocusNode = nodesObj[0].n; currentFocusNode.focus()}			
	}	
	const reg = (o) => {
		callbacks.push(o)
		const unreg = () => {
			const index = callbacks.indexOf(o)
			if(index>=0) callbacks.splice(index,1)
		}
		return {unreg}
	}
	const switchTo = (node) => {						
		const roNode = callbacks.find(o=>o.el == currentFocusNode)
		if(roNode&&roNode.state.focused) roNode.onBlur()
		currentFocusNode = node.el		
	}
	const checkActivate = doCheck
	const focusTo = (data) => setTimeout(()=>{		
		const preferedFocusObj = callbacks.find(o=>o.el.classList.contains(`marker-${data}`))
		if(preferedFocusObj) {
			switchTo(preferedFocusObj)
			preferedFocusObj.el.focus()
		}
	},200)
	const receivers = {focusTo}
	return {reg,switchTo,checkActivate,receivers}
}