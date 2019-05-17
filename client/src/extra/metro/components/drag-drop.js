"use strict";
import React 	from 'react'
import {dragDropModule, dragDropPositionStates} from "../../dragdrop-module"
import {images} from "../media/images.js"


const $ = React.createElement

const DragDropHandlerElement = (props) => {	
	const elem = React.useRef(null)
	const report = (action,fromSrcId,toSrcId,side) =>{			
		if((!Array.isArray(props.filterActions)||props.filterActions.includes(action))&&props.onDragDrop)
			props.onDragDrop("reorder",JSON.stringify({action,fromSrcId,toSrcId, side:side?side:""}))
	}
	React.useEffect(()=>{
		elem.current.dragBinding = dragDropModule.regReporter(report)
		return ()=>{
			elem.current.dragBinding && elem.current.dragBinding.release()
		}
	},[])

	return $('span',{ref:elem,className:"dragDropHandler"})	
}

const DragDropDivElement  = (props) => {
	const [state, setState] = React.useState({side:dragDropPositionStates.none,offSet:null})
	const elem = React.useRef(null)	
	
	const onMouseOut = (e) =>{
		if(state.side != dragDropPositionStates.none) setState({side:dragDropPositionStates.none})
	}
	const updateState = v =>{
		if(state.side !== v) {				
			const o = (el,_2) => (el?{el,rect:el.getBoundingClientRect(),s:_2}:null)
			const a = (_ => {
				switch(v){
					case dragDropPositionStates.left: 							
						return o(elem.current.previousElementSibling,-1)
					case dragDropPositionStates.right: 							
						return o(elem.current.nextElementSibling,1)					
					default: return null;
				}
			})()
			const thisElRect = elem.current.getBoundingClientRect()
			const b = a && (Math.abs(a.rect.top - thisElRect.top) < thisElRect.height) && a
			const offSet = b?a.s * (a.s>0?thisElRect.right - a.rect.left:thisElRect.left - a.rect.right)/2:0				
			setState({side:v,offSet})
		}			
	}
	React.useEffect(()=>{
		elem.current.dragBinding = dragDropModule.dragReg({node:elem.current,dragData:props.dragData})		
		elem.current.actions = {
			onMouseMove: e => {if(props.dragover) elem.current.dragBinding.dragOver(e,elem.current, updateState)},
			onMouseUp: e =>{
				const {clientX,clientY} = e.type.includes("touch")&&e.touches.length>0?{clientX:e.touches[0].clientX,clientY:e.touches[0].clientY}:{clientX:e.clientX,clientY:e.clientY}
				const elements = clientX&&clientY?e.target.ownerDocument.elementsFromPoint(clientX,clientY):[]
				if(!elements.includes(elem.current)) return			
				if(!props.droppable) return
				elem.current.dragBinding.dragDrop(e,elem.current)
				onMouseOut(e)
			}	
		}
		elem.current.ownerDocument.defaultView.addEventListener("mouseup",elem.current.actions.onMouseUp)
		elem.current.addEventListener("mousemove",elem.current.actions.onMouseMove)
		
		return () =>{
			elem.current.dragBinding.release()
			elem.current.ownerDocument.defaultView.removeEventListener("mouseup",elem.current.actions.onMouseUp)
			elem.current.removeEventListener("mousemove",elem.current.actions.onMouseMove)
		}
	},[])
	React.useEffect(()=>{
		elem.current.dragBinding.update({node:elem.current,dragData:props.dragData})
	},[props])		
	
	const onMouseDown = e =>{
		if(!props.draggable) return
		elem.current.dragBinding.dragStart(e,elem.current,"div",props.dragStyle)
	}	
			
	const v = {border:"1px solid"}
	const borderL = state.side == dragDropPositionStates.left?v:{}
	const borderR = state.side == dragDropPositionStates.right?v:{}
	const infoS = {position:"absolute",boxSizing:"border-box",height:"100%",top:"0"}
	const stO = state.side != dragDropPositionStates.none? {position:"relative"}:{}
	const style = {
		...props.style,
		...stO
	}
	const draw2 = (v) => Object.values(v).length>0 
	const actions = {				
		onMouseDown:onMouseDown,				
		onTouchStart:onMouseDown,
		onTouchEnd:e=>elem.current.actions.onMouseUp(e),
		onMouseOut:onMouseOut
	}
	const stStyleL = {
		...infoS,
		...borderL,
		left:state.offSet?state.offSet+"px":"0"
	}
	const stStyleR = {
		...infoS,
		...borderR,
		right:state.offSet?state.offSet+"px":"0"
	}
	const iStyle = {
		height: "0.3em",
		position: "absolute",
		transformOrigin: "center center",
		zIndex:"1"
	}
	const iStyleT = {				
		...iStyle,
		transform: "rotate(180deg)",				
		left: "-0.25em",
		top: "-0.1em"
	}
	const iStyleB = {
		...iStyle,
		transform: "rotate(0deg)",				
		left: "-0.25em",
		bottom: "-0.1em"
	}	
	const className = "DragDropDivElement"
	const getSvgData = () =>{
		if(!elem.current) return
		return images(elem.current).triAngleSvgData
	}
	return $("div",{style,ref:elem,...actions,className},[
		$("div",{style:stStyleL,key:1},draw2(borderL)?[$("img",{key:1,src:getSvgData(),style:iStyleT}),$("img",{key:2,src:getSvgData(),style:iStyleB})]:null),
		$("div",{key:2},props.children),
		$("div",{style:stStyleR,key:3},draw2(borderR)?[$("img",{key:1,src:getSvgData(),style:iStyleT}),$("img",{key:2,src:getSvgData(),style:iStyleB})]:null)
	])
		
}
	
export {DragDropHandlerElement, DragDropDivElement}