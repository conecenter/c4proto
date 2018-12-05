export default function DragDropModule({log,documentManager,windowManager}){
	let cNode = null;
	let dragNode = null;
	let ddNode = null
	let listRect = null;
	let scrollNodes = null;
	const reporters = [];
	const dragElements = [];
	const mouseHitPoint = {x:0,y:0}
	const curMousePoint = {x:0,y:0}

	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => callbacks.push(c)
		const remove = (c) => {
			const index = callbacks.indexOf(c)
			callbacks.splice(index,1)
		}
		const check = () => callbacks.forEach(c=>c())
		return {add,remove,check}
	})();
	const {addEventListener,removeEventListener,getPageYOffset} = windowManager
	
	const findScrollNodes = (childNode) => {
		if(!cNode) return
		const htmlNode = documentManager.body().parentNode			
		while(childNode != htmlNode && (childNode.clientHeight<=childNode.parentNode.clientHeight || childNode.parentNode.clientHeight == 0))
			childNode = childNode.parentNode
		return {childNode,parentNode:childNode.parentNode}
	}
	const doCheck = () =>{
        if(!scrollNodes) return
		const pHeight = scrollNodes.parentNode.clientHeight
		if(curMousePoint.y <= 10) scrollNodes.childNode.scrollTop = parseInt(scrollNodes.childNode.scrollTop)>15?scrollNodes.childNode.scrollTop - 25:0
		else
		if(curMousePoint.y <= 30) scrollNodes.childNode.scrollTop = parseInt(scrollNodes.childNode.scrollTop)>9?scrollNodes.childNode.scrollTop - 15:0
		else
		if(curMousePoint.y <= 60) scrollNodes.childNode.scrollTop = parseInt(scrollNodes.childNode.scrollTop)>5?scrollNodes.childNode.scrollTop - 5:0
		
		if(curMousePoint.y >= pHeight - 10) scrollNodes.childNode.scrollTop = parseInt(scrollNodes.childNode.scrollTop)<scrollNodes.childNode.clientHeight - scrollNodes.parentNode.clientHeight? scrollNodes.childNode.scrollTop + 25:scrollNodes.childNode.clientHeight - scrollNodes.parentNode.clientHeight
		else
		if(curMousePoint.y >= pHeight - 30) scrollNodes.childNode.scrollTop = parseInt(scrollNodes.childNode.scrollTop)<scrollNodes.childNode.clientHeight - scrollNodes.parentNode.clientHeight? scrollNodes.childNode.scrollTop + 15:scrollNodes.childNode.clientHeight - scrollNodes.parentNode.clientHeight
		else
		if(curMousePoint.y >= pHeight - 60) scrollNodes.childNode.scrollTop = parseInt(scrollNodes.childNode.scrollTop)<scrollNodes.childNode.clientHeight - scrollNodes.parentNode.clientHeight? scrollNodes.childNode.scrollTop + 5:scrollNodes.childNode.clientHeight - scrollNodes.parentNode.clientHeight
	}
	const dragEnd = () => {
		if(!cNode) return false;			
		documentManager.remove(cNode);			
		cNode = null;
		dragNode = null;
		listRect = null;
		scrollNodes = null;
		//reporters.splice(0)
		removeEventListener("mousemove",onMouseMove)
		removeEventListener("mouseup",onMouseUp)
		removeEventListener("touchend",onMouseUp)
		removeEventListener("touchmove",onMouseMove)
		removeEventListener("keydown",onKeyDown)
		checkActivateCalls.remove(doCheck)
		return false;
	}
	const onMouseMove = (event) => {
		if(!cNode) return;
		if(cNode.style.display == "none") {
			cNode.style.display = ""
			report("dragStart",dragNode)
		}
		const {x,y} = getXY(event)
		curMousePoint.y = y
		curMousePoint.x = x
		const offsetT = mouseHitPoint.top - mouseHitPoint.y  + getPageYOffset() + y + "px"
		const offsetL =  mouseHitPoint.left - mouseHitPoint.x + x + "px"
		cNode.style.top = offsetT
		cNode.style.left = offsetL		
		event.preventDefault();
	}
	const onMouseUp = (event) => {
		if(listRect){
            const {x,y} = getXY(event)
			if(
				y>=listRect.top&&
				y<=listRect.bottom&&
				x>=listRect.left&&
				x<=listRect.right				
			)   
				onDrag()&&report("dragEnd",dragNode)
			else
				onDrag()&&report("dragEndOutside",dragNode)
		}
		dragEnd();
	}
	const getListNode = (node) =>{
		while(node.tagName!="TABLE")
			node = node.parentNode
		return node
	}
	const getListRect = (node) => getListNode(node).getBoundingClientRect()
	const isTouch = (event) => event.type.includes("touch") && event.touches.length>0
	const getXY = (event) =>  isTouch(event)?{x:event.touches[0].clientX,y:event.touches[0].clientY}:{x:event.clientX,y:event.clientY}
	const getTarget = (event) => {
		//if(isTouch(event)){
			const {x,y} = getXY(event)
			return documentManager.nodeFromPoint(x,y + getPageYOffset())
	//	}
	//	else 
	//		return event.target		
	}
	const regReporter = (callback) => {
		reporters.push(callback)
		const release = () =>{		
			const index = reporters.indexOf(callback)
			reporters.splice(index,1)		
		}
		return ({release})
		
	}
	const dragReg = (dragEl) => {
		dragElements.push(dragEl)
		const release = () =>{
			const index = dragElements.indexOf(dragEl)
			dragElements.splice(index,1)
			dragEnd()
		}
		const update = (props) =>{
			const index = dragElements.indexOf(dragEl)			
			dragElements[index].dragData = props.dragData						
		}
		return ({update,release,dragOver,dragStart,dragDrop})
	}
	const dragOver = (node) => {
		if(onDrag()) report("dragOver",dragNode,node)
	}
	const dragDrop = (node) => {
		if(onDrag() && getData(node)) {			
			report("dragDrop",dragNode,node)
			dragEnd()
		}
	}
	const report = (action,fromNode, toNode) => {
		let fromSrcId = "",toSrcId =""
		const fEl = dragElements.find(el=> fromNode && el.node == fromNode)
		if(fEl)
			fromSrcId = fEl.dragData?fEl.dragData:""
		const tEl = dragElements.find(el=> toNode && el.node == toNode)
		if(tEl)
			toSrcId = tEl.dragData?tEl.dragData:""
		reporters.forEach(r=>r(action,fromSrcId,toSrcId))
	}
	const dragStart = (event,node,div,style) => {
		const {x,y} = getXY(event)
		let listNode
		let refNode
		const old = Array.from(documentManager.body().querySelectorAll(".dragClone"))
		old.forEach(o=>documentManager.remove(o))
		if(!div){ //td
			cNode = documentManager.createElement("table")	
			Object.assign(cNode.style,style)
			listRect = getListRect(node);
			listNode = getListNode(node);					
			//callbacks.push(callback)
			refNode = node.parentNode
			cNode.appendChild(node.parentNode.cloneNode(true));
		}
		else{ //div
			cNode = node.cloneNode(true)			
			Object.assign(cNode.style,style)
			listRect = node.getBoundingClientRect()
			listNode = node			
			refNode = node
		}
		cNode.style.display = "none"
		cNode.className ="dragClone"
		scrollNodes = findScrollNodes(listNode)	
		dragNode = node	
		const refRect = refNode.getBoundingClientRect();			
		const top = refRect.top + getPageYOffset()
		cNode.style.width = refRect.width + "px";
		cNode.style.position="absolute";
		cNode.style.top = top + "px"
		cNode.style.left = refRect.left + "px"
		cNode.style.opacity = "0.7"
		cNode.style.pointerEvents = "none";
		documentManager.add(cNode)
		mouseHitPoint.x = x
		mouseHitPoint.y = y
		mouseHitPoint.top = refRect.top
		mouseHitPoint.left = refRect.left
		addEventListener("mousemove",onMouseMove)
		addEventListener("touchmove",onMouseMove)
		addEventListener("mouseup",onMouseUp)
		addEventListener("touchend",onMouseUp)
		addEventListener("keydown",onKeyDown)
		//checkActivateCalls.add(doCheck)				
		//return ({release});
	}
	const getData = (node) => {const el = dragElements.find(el=>el.node == node); if(el) return el.dragData; else return null;}
	const onDrag = () => cNode&&cNode.style.display!="none"		
	const onKeyDown = (event) => {
		if(event.key == "Escape") onEsc()
	}
	const onEsc = () => {
        report("dragEndOutside",dragNode)			
		dragEnd()	
	}
	const checkActivate = checkActivateCalls.check
	// multiselect dragdrop
	let lastSwappedNode = null
	const releaseDD = () =>{
		removeEventListener("mouseup",onMouseUpDD)
		removeEventListener("touchend",onMouseUpDD)		
		removeEventListener("mousemove",onMouseMoveDD)
		removeEventListener("touchmove",onMouseMoveDD)		
		dragElements.splice(0)		
		if(!ddNode) return		
		documentManager.remove(ddNode)
		ddNode = null
		lastSwappedNode = null
	}
	const onMouseUpDD = (event) =>{
		let posCol = []
		if(ddNode){
			for(let i =0;i<ddNode.children.length;i+=1)
				if(ddNode.children[i].tagName=="DIV")
				posCol.push(ddNode.children[i].dataset.srcKey)
			dragElements.forEach(c=>c(posCol))
		}		
		releaseDD()
	}
	const swapNodes = (node1I,node2I) =>{
		const node1 = ddNode.children[node1I]
		const node2 = ddNode.children[node2I]
		if(node1I<node2I){
			if(node2I+1>ddNode.children.length-1) return null
			const node2 = ddNode.children[node2I+1]			
			ddNode.insertBefore(node1,node2)			
		}
		else{
			//const node2 = ddNode.children[node2I]			
			ddNode.insertBefore(node1,node2)		
		}		
		return node2
	}
	const onMouseMoveDD = (event) =>{
		if(!ddNode) return				
        const overNode = getTarget(event)		
		const tIndex = findChildDD(ddNode,overNode)
		const selectedNode = ddNode.querySelector(".selected")
		const selectedNodeI = findChildDD(ddNode,selectedNode)		
		if(tIndex<0) return		
		if(lastSwappedNode == ddNode.children[tIndex]) return
		
		if(ddNode.children[selectedNodeI] != ddNode.children[tIndex]) lastSwappedNode = swapNodes(selectedNodeI,tIndex)		
		//event.preventDefault();
		event.preventDefault();
	}
	const findChildDD = (parent,node) =>{
		let n = node
		let i=-1
		while(n && n!=parent) {
			let found = false
			for(i=0;i<parent.children.length;i+=1){
				if(parent.children[i] == n) {found = true;break}				
			}
			if(found) break
			i=-1
			n = n.parentNode
		}
		return i
	}	
	const dragStartDD = (event,node,callback) =>{
		if(!event.target.classList.contains("button")) return null
		const tIndex = findChildDD(node,getTarget(event))
		if(tIndex<0) return null
		ddNode = node.cloneNode(true)
		dragElements.push(callback)
		const oRect = node.getBoundingClientRect()
		ddNode.style.width = oRect.width + "px"
		ddNode.style.height = oRect.height + "px"
		ddNode.style.position = "absolute"
		ddNode.style.left = oRect.left + "px"
		ddNode.style.top = (oRect.top + getPageYOffset()) + "px"
		//ddNode.style.userSelect = "none"
		//ddNode.style.MozUserSelect = "none"
		ddNode.style.zIndex = "9999"
	
		const selectedNode = ddNode.children[tIndex]		
		selectedNode.style.opacity = '0.3'
		selectedNode.classList.add("selected")
		ddNode.style.lineHeight = "1"
		documentManager.add(ddNode)
		for(let i =0;i<ddNode.children.length;i+=1) {
			if(ddNode.children[i].tagName!="DIV") continue
			const child = ddNode.children[i]			
			if(!child.dataset.srcKey) {
				const srcKeyNode = child.querySelector("[data-src-Key]")
				child.dataset.srcKey = srcKeyNode?srcKeyNode.dataset.srcKey:i//child.firstElementChild?child.firstElementChild.dataset.srcKey:i            
			}
		}
		addEventListener("mousemove",onMouseMoveDD)
		addEventListener("touchmove",onMouseMoveDD)
		addEventListener("mouseup",onMouseUpDD)
		addEventListener("touchend",onMouseUpDD)		
		return  ({releaseDD})
	}
	return {dragReg,checkActivate,regReporter,dragStartDD}
}