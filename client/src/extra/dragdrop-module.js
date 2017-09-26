export default function DragDropModule({log,documentManager,windowManager}){
	let cNode = null;
	let ddNode = null
	let cNodeData = null;
	let listRect = null;
	let scrollNodes = null;
	const callbacks = [];
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
	const release = () => {
		if(!cNode) return false;			
		documentManager.remove(cNode);			
		cNode = null;
		listRect = null;
		scrollNodes = null;
		callbacks.splice(0)
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
			)   outOfParent(false)
			else
				outOfParent(true)
		}
		release();
	}
	const getListNode = (node) =>{
		while(node.tagName!="TABLE")
			node = node.parentNode
		return node
	}
	const getListRect = (node) => getListNode(node).getBoundingClientRect()
	const isTouch = (event) => event.type.includes("touch")
	const getXY = (event) =>  isTouch(event)?{x:event.touches[0].clientX,y:event.touches[0].clientY}:{x:event.clientX,y:event.clientY}
	const getTarget = (event) => {
		if(isTouch(event)){
			const {x,y} = getXY(event)
			return documentManager.nodeFromPoint(x,y + getPageYOffset())
		}
		else 
			return event.target		
	}
	const dragStart = (event,node,data,callback) => {
		const {x,y} = getXY(event)		
		cNode = documentManager.createElement("table")
		cNodeData = data;
		listRect = getListRect(node);
		const listNode = getListNode(node);
		scrollNodes = findScrollNodes(listNode)		
		callbacks.push(callback)
		cNode.appendChild(node.parentNode.cloneNode(true));
		const parentRect = node.parentNode.getBoundingClientRect();			
		const top = parentRect.top + getPageYOffset()
		cNode.style.width = node.parentNode.getBoundingClientRect().width + "px";
		cNode.style.position="absolute";
		cNode.style.top = top + "px"
		cNode.style.left = parentRect.left + "px"
		cNode.style.opacity = "0.7"
		cNode.style.pointerEvents = "none";
		documentManager.add(cNode)
		mouseHitPoint.x = x
		mouseHitPoint.y = y
		mouseHitPoint.top = parentRect.top
		mouseHitPoint.left = parentRect.left
		addEventListener("mousemove",onMouseMove)
		addEventListener("touchmove",onMouseMove)
		addEventListener("mouseup",onMouseUp)
		addEventListener("touchend",onMouseUp)
		addEventListener("keydown",onKeyDown)
		checkActivateCalls.add(doCheck)
		return ({release});
	}
	const getData = () => cNodeData
	const onDrag = () => cNode&&true		
	const onKeyDown = (event) => {
		if(event.key == "Escape") onEsc()
	}
	const onEsc = () => {			
		outOfParent(true)
		release()
		cNodeData = null
	}
	const outOfParent = (outside) => {			
		callbacks.forEach(c=>c(outside))
	}		
	const checkActivate = checkActivateCalls.check
	// multiselect dragdrop
	let lastSwappedNode = null
	const releaseDD = () =>{
		removeEventListener("mouseup",onMouseUpDD)
		removeEventListener("touchend",onMouseUpDD)
		removeEventListener("mousemove",onMouseMoveDD)
		removeEventListener("touchmove",onMouseMoveDD)		
		callbacks.splice(0)
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
				posCol.push(ddNode.children[i].dataset.index)
		}		
		callbacks.forEach(c=>c(posCol))
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
		callbacks.push(callback)
		const oRect = node.getBoundingClientRect()
		ddNode.style.width = oRect.width + "px"
		ddNode.style.height = oRect.height + "px"
		ddNode.style.position = "absolute"
		ddNode.style.left = oRect.left + "px"
		ddNode.style.top = (oRect.top + getPageYOffset()) + "px"		
		const selectedNode = ddNode.children[tIndex]		
		selectedNode.style.opacity = '0.3'
		selectedNode.classList.add("selected")
		ddNode.style.lineHeight = "1"
		for(let i =0;i<ddNode.children.length;i+=1) ddNode.children[i].dataset.index = i
		
		documentManager.add(ddNode)
		addEventListener("mouseup",onMouseUpDD)
		addEventListener("touchend",onMouseUpDD)
		addEventListener("mousemove",onMouseMoveDD)
		addEventListener("touchmove",onMouseMoveDD)
		return  ({releaseDD})
	}
	return {dragStart,getData,onDrag,release,checkActivate,
			dragStartDD			
	}
}