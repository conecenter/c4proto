import {rootCtx} from "../main/vdom-util"
import {eventManager} from './event-manager.js'

export default function FocusModule({log,documentManager,windowManager}){		
	let nodesObj = [];
	let lastMousePos = {};
	let stickyNode = null;
	const {document} = documentManager
	const {addEventListener,setTimeout} = windowManager	
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
	const getParentWrapper = (el) => {
		let e = el
		while(e){
			if(e.classList.contains("focusWrapper")) return e
			e = e.parentElement
		}
		return null
	}
	const mapNodes =() =>{
		const aEl = documentManager.activeElement()
		nodesObj = Array.from(aEl.ownerDocument.querySelectorAll(".focusWrapper")).map(n=>{
			const r = n.getBoundingClientRect()				
			return {y0:r.top,x0:r.left,y1:r.bottom,x1:r.right,n:n}
		})
	}
	const getCNode = () =>{
		const aEl = documentManager.activeElement()
		if(aEl.tagName == "IFRAME") {
			return getParentWrapper(aEl.contentDocument.activeElement)
		}
		return getParentWrapper(aEl)		
	}
	const findBestDistance = (axis) => {
		const aEl = documentManager.activeElement()
		if((axis==2||axis==0) && aEl.tagName == "INPUT") return
		mapNodes()
	    const wrap = getCNode()	
		const index = nodesObj.findIndex(o => o.n == wrap)
		if(index<0) return
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
		const cNode = getCNode()
		if(!cNode) return
		const controlEl = cNode.querySelector("input") || cNode.querySelector("textarea") || cNode.querySelector("button,.button")
		const innerTab = cNode.querySelector('[tabindex="1"]')
		const cEvent = event()
		if(controlEl) 
			controlEl.dispatchEvent(cEvent)
		else if(innerTab)				
			innerTab.dispatchEvent(cEvent)
		else
			cNode.dispatchEvent(cEvent)
	}
	const onKeyDown = (event) =>{
		//if(nodesObj.length == 0) return		
		let best = null	
        let isPrintable = false		
		const vk = event.code == "vk" || event.detail && event.detail.code == "vk"
		const eventKey = event.key || event.keyCode && String.fromCharCode(event.keyCode) || event.detail && event.detail.key
		const detail = {key:eventKey,vk}
		switch(eventKey){
			case "ArrowUp":
				best = findBestDistance(3);break;
			case "ArrowDown":
				best = findBestDistance(1);break;
			case "ArrowLeft":
				best = findBestDistance(2);break;
			case "ArrowRight":
				best = findBestDistance(0);break;
			case "Escape":
				//currentFocusNode&&currentFocusNode.el.focus();
				break;			
			case "Tab":				 
				//currentFocusNode&&currentFocusNode.el.focus();
				onTab(event,vk)
				event.preventDefault();return;
			case "F2":	
			case "Enter":
				sendEvent(()=>eventManager.create(document)("enter",{detail}));
				break;
			case "Erase":
				sendEvent(()=>eventManager.create(document)("erase"));break;
			case "Delete":
			    sendEvent(()=>eventManager.create(document)("delete"));break;	
			case "Backspace":
				sendEvent(()=>eventManager.create(document)("backspace",{detail}));break;			
			case "Insert":
			case "c":
				if(event.ctrlKey){
					//sendEvent(()=>eventManager.create(document)("ccopy"))
					break
				}
				isPrintable = true
				break
			case "F1":
			case "F2":
			case "F3":
			case "F4":
			case "F5":
			case "F6":
			case "F7":
			case "F8":
			case "F9":
			case "F10":
				break;
			case " ":
				if(event.target.tagName !== "INPUT" && event.target.tagName !== "TEXTAREA")
					event.preventDefault()
			default:
				isPrintable = true
		}		
		if(best) best.o.n.focus();				
		if(isPrintable && isPrintableKeyCode(eventKey)) {			
			sendEvent(()=>eventManager.create(document)("delete",{detail}))
			/*const cRNode = callbacks.find(o=>o.el == currentFocusNode&&currentFocusNode.el)			
			if(cRNode && cRNode.props.sendKeys) sendToServer(cRNode,"key",event.key)*/
		}			
	}
	const sendToServer = (cRNode,type,action) => {if(cRNode.props.onClickValue) cRNode.props.onClickValue(type,action)}
	const ifLastThenEnter = (index,nodes) =>{
		const sb = 'button[type="submit"]'
		if(!nodes.slice(index).find(_=>_.querySelector("input"))) {
			const node=nodes.slice(index).find(_=>_.querySelector(sb))
			if(node) node.querySelector(sb).click()
		}
	}
	const onTab = (event,vk) =>{		
		const cNode = getCNode()
		if(!cNode){		
			mapNodes()
			return nodesObj[0]&&nodesObj[0].n.focus()	
		}
		const root = vk?(cNode&&cNode.ownerDocument):event.target.ownerDocument
		if(!root) return
		const nodes = Array.from(root.querySelectorAll('[tabindex="1"]'))		
		/*const cRNode = callbacks.find(o=>currentFocusNode&&true && o.el == currentFocusNode.el)
		if(cRNode&&cRNode.props.autoFocus == false){
			sendToServer(cRNode,"focus","change")
			return
		}*/		
		const cIndex = nodes.findIndex(n=> n == cNode)
		if(cIndex>=0) {
			if(cIndex+1<nodes.length) {
				nodes[cIndex+1].focus()				
			}
			else{
				setTimeout(()=>{
					const nodes = Array.from(root.querySelectorAll('[tabindex="1"]'))		
					const cIndex = nodes.findIndex(n=>n == cNode)
					if(cIndex>=0) {
						if(cIndex+1<nodes.length) {
							nodes[cIndex+1].focus()							
						}
						else 
							cNode&&cNode.focus()
					}					
				},200)
			}				
		}		
	}
	const onEnter = (event) =>{
		const root = event.target.ownerDocument
		if(!root) return
		const detail = event.detail
		if(!detail) return		
		const marker = `marker-${detail}`
		const btn = root.querySelector(`button.${marker}`)
		if(btn) {
			btn.dispatchEvent(eventManager.create(document)("click",{bubbles:true}))
		}
	}
	const getLastClickNode = () =>{
		const {x,y} = lastMousePos
		return x&&y&&documentManager.nodeFromPoint(x,y)		
	}
	const onMouseDown = (e) => {
		lastMousePos = {x:e.clientX,y:e.clientY}
	}
	const onPaste = (event) => {
		const data = event.clipboardData.getData("text")
		sendEvent(()=>eventManager.create(document)("cpaste",{detail:data}))
	}		
	addEventListener("keydown",onKeyDown)
	addEventListener("paste",onPaste)
	addEventListener("cTab",onTab)		
	addEventListener("cEnter",onEnter)
	addEventListener("mousedown",onMouseDown,true)
	const isPrintableKeyCode = (ch)	=> ch&&("abcdefghijklmnopqrtsuvwxyz1234567890.,*/-+:;&%#@!~? ".split('').some(c=>c.toUpperCase()==ch.toUpperCase()))
	const isVk = (el) => el.classList.contains("vkElement")	
	const focusTo = (data) => setTimeout(()=>{
		mapNodes()
		const preferedFocusObj = nodesObj.find(o=>o.n.dataset.path&&o.n.dataset.path.includes(data))
		preferedFocusObj && preferedFocusObj.n.focus()
	},200)
	const toView = (className)=>setTimeout(()=>{
		const o = document.querySelector(`.${className}`)
		o&&o.scrollIntoViewIfNeeded(false)
	})	
	const receivers = {focusTo,toView}
	return {receivers}
}