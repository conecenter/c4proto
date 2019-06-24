"use strict";
import React 	from 'react'
import {checkActivateCalls} from '../../event-manager.js'
import GlobalStyles from '../global-styles.js'

const $ = React.createElement

const MenuBurger = (props) => {
	const c = {transition:"all 100ms",transformOrigin:"center"}
	const alt1 = props.isBurgerOpen?{transform: "rotate(-45deg)"}:{}
	const alt2 = props.isBurgerOpen?{opacity: "0"}:{}
	const alt3 = props.isBurgerOpen?{transform: "rotate(45deg)"}:{}	
	const color = props.style&&props.style.color?props.style.color:"white"
	const svg = $("svg",{xmlns:"http://www.w3.org/2000/svg","xmlnsXlink":"http://www.w3.org/1999/xlink",height:"1.5em",width:"1.5em", style:{"enableBackground":"new 0 0 32 32"}, version:"1.1", viewBox:"0 0 32 32","xmlSpace":"preserve"},[				
			$("line",{style:{...c,...alt1},key:1,"strokeLinecap":"round",x1:"2",y1:props.isBurgerOpen?"16":"9",x2:"30",y2:props.isBurgerOpen?"16":"9","strokeWidth":"4","stroke":color}),							
			$("line",{style:{...c,...alt2},key:2,"strokeLinecap":"round",x1:"2",y1:"17",x2:"30",y2:"17","strokeWidth":"4","stroke":color}),								
			$("line",{style:{...c,...alt3},key:3,"strokeLinecap":"round",x1:"2",y1:props.isBurgerOpen?"16":"25",x2:"30",y2:props.isBurgerOpen?"16":"25","strokeWidth":"4","stroke":color})				
	])
	const style = {
		backgroundColor:"inherit",
		cursor:"pointer",
		...props.style
	}
	return $("div",{style,onClick:props.onClick},svg)
}
	
const MenuBarElement = (props) => {	
 
	const elem = React.useRef(null)
	const leftElem = React.useRef(null)
	
	const [state, setBurger] = React.useState({isBurger:false,bpLength:null})
	
	React.useEffect((c)=>{
		elem.current.actions = {
			calc: ()=>{
				if(!leftElem.current) return
				const tCLength = Math.round(Array.from(leftElem.current.children).reduce((a,e)=>a+e.getBoundingClientRect().width,0))
				const tLength = Math.round(leftElem.current.getBoundingClientRect().width)
				
				if(!state.bpLength && tCLength>0 && tCLength>=tLength && !state.isBurger) {					
					setBurger({isBurger:true, bpLength:tCLength})					
				}
				if(state.bpLength && state.bpLength<tLength && state.isBurger){										
					setBurger({isBurger:false, bpLength:null})							
				}
			}
		}		
		checkActivateCalls.add(elem.current.actions.calc)
		return ()=>{
			checkActivateCalls.remove(elem.current.actions.calc)
		}
	},[state])		
	const openBurger = (e) => {
		props.onClick && props.onClick(e)			
	}	
	const parentEl = (node) =>{
		if(!leftElem.current||!node) return true
		let p = node
		while(p && p !=leftElem.current){
			p = p.parentElement
			if(p == leftElem.current) return true
		}
		return false
	}
	const onBurgerBlur = (e) =>{
		if(props.isBurgerOpen && !parentEl(e.relatedTarget)) openBurger(e)
	}		
	const menuStyle = {
		position:"static",
		width:"100%",
		zIndex:"6662",
		top:"0rem",			
		...props.style				
	}
	const barStyle = {
		display:'flex',
		flexWrap:'nowrap',
		justifyContent:'flex-start',
		//backgroundColor:'#2196f3',
		//color:"white",
		verticalAlign:'middle',				
		width:"100%",
		...props.style				
	}
	const burgerPopStyle = {
		position:"absolute",
		zIndex:"1000",
		backgroundColor:"inherit"
	}
	const left = props.children.filter(_=>_.key&&!_.key.includes("right"))									
	const right = props.children.filter(_=>!_.key||_.key.includes("right"))
	const menuBurger = $("div",{onBlur:onBurgerBlur,tabIndex:"0", style:{backgroundColor:"inherit",outline:"none"}},[
		$(MenuBurger,{style:{marginLeft:"0.5em"},isBurgerOpen:props.isBurgerOpen,key:"burger",onClick:openBurger}),
		props.isBurgerOpen?$("div",{style:burgerPopStyle,key:"popup"},left):null
	])
	const className = "menuBar "+props.className
	return $("div",{},
		$("div",{style:barStyle,className,ref:elem},[
			$("div",{key:"left", ref:leftElem,style:{whiteSpace:"nowrap",backgroundColor:"inherit",flex:"1",alignSelf:"center",display:"flex"}},state.isBurger?menuBurger:left),
			$("div",{key:"right",style:{alignSelf:"center"}},right)
		])				
	)
		
}	
const MenuDropdownElement = (props) =>{ 		
	const elem = React.useRef(null)
	const [stateRight,setRight] = React.useState(null)
	
	const getParentNode = React.useCallback((childNode,className)=>{
		let parentNode = childNode.parentNode
		while(parentNode!=null&&parentNode!=undefined){
			if(parentNode.classList.contains(className)) break
			parentNode = parentNode.parentNode;
		}
		return parentNode
	},[])
	const calc = (el)=>{
		if(!el) return
		const menuRoot = getParentNode(el,"menuBar")
		const maxRight = menuRoot.getBoundingClientRect().right
		const elRight = el.getBoundingClientRect().right			
		if(elRight>maxRight){				
			if(stateRight != 0) setRight(0)
		}
	}
	React.useEffect(()=>{
		calc(elem.current)		
	})	
			 
	const re = /\d+(\.\d)?%/ //100%
	const isLeft = re.test(props.style.left)
	const sideStyle = isLeft && (stateRight!==null)?{right:"100%",left:""}:{}
	const ieFix = stateRight!==null?{right:stateRight+"px"}:{left:"0px"}
	const {boxShadow,borderWidth,borderStyle} = GlobalStyles	
	return $("div",{
		ref:elem,
		className:props.className,
		style: {
			position:'absolute',					
			minWidth:'7em',					
			boxShadow:boxShadow,
			zIndex:'10002',
			transitionProperty:'all',
			transitionDuration:'0.15s',
			transformOrigin:'50% 0%',
			borderWidth:borderWidth,
			borderStyle:borderStyle,
			borderColor:"#2196f3",		//maxHeight:this.state.maxHeight,				
			...ieFix,
			...props.style,
			...sideStyle
		}
	},props.children)					
}
const FolderMenuElement = React.memo((props) =>{
	const elem = React.useRef(null)	
	React.useEffect(()=>{
		elem.current.actions = {
			onClick:e =>{props.onClick && props.onClick(e); e.stopPropagation()}
		}
		elem.current.addEventListener("click",elem.current.actions.onClick)			
		return ()=>{ 						
			elem.current.removeEventListener("click",elem.current.actions.onClick)				
		}
	},[props.onClick])
	const selStyle={
		position:'relative',		
		whiteSpace:'nowrap',
		paddingRight:'0.8em',
		cursor:"pointer",
		outline:"none",
		...props.style		
	}		
	const className = "menu-popup " + props.className
	return $("div",{
		ref:elem,
		style:selStyle,					    
		className,
		tabIndex:"1",
	},props.children)
})	
const ExecutableMenuElement = (props) => {
	//const [mEnter, setEnter] = React.useState(false)
	const elem = React.useRef(null)
	React.useEffect(()=>{
		elem.current.actions = {
			onClick:e=> {props.onClick && props.onClick(e); e.stopPropagation()}
		}
		elem.current.addEventListener("click",elem.current.actions.onClick)
		return () => {
			elem.current.removeEventListener("click",elem.current.actions.onClick)
		}
	},[props.onClick])	
	const newStyle={
		minWidth:'7em',             
		cursor:'pointer',
		...props.style		
	}
	return $("div",{
		ref:elem,
		style:newStyle,    
		className:props.className		           
	},props.children)
	
}

export {MenuBarElement, MenuDropdownElement, FolderMenuElement, ExecutableMenuElement, MenuBurger}