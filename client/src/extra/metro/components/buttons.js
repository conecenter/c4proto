"use strict";
import React 	from 'react'
import ReactDOM from 'react-dom'
import GlobalStyles from '../global-styles.js'
import {eventManager,checkActivateCalls} from '../../event-manager.js'

const initialButtonState = {mouseOver:false,touchStart:false}
const buttonReducer = (state,action) => {
	switch(action.type){
		case 'mouseover':
			return {...state,mouseOver: action.value}
		case 'touchstart':
			return {...state,touchStart: action.value}
		default:
			return state
	}
}

const $ = React.createElement
const ButtonElement = (props) => {	
	const [state,dispatch] = React.useReducer(buttonReducer,initialButtonState)
	const elem = React.useRef(null)
	const defbg = "#eeeeee"
	const bg = props.style&&props.style.backgroundColor?props.style.backgroundColor:defbg					
	const disabled = props.changing?true:null
	const style={
		border:'none',
		cursor:'pointer',
		paddingInlineStart:'0.4em',
		paddingInlineEnd:'0.4em',
		padding:'0 1em',
		minHeight:'1em',
		minWidth:'1em',
		fontSize:'1em',
		alignSelf:'center',
		fontFamily:'inherit',				
		outline:state.touchStart?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
		outlineOffset:GlobalStyles.outlineOffset,
		...props.style,			
		...(state.mouseOver && Object.keys(props.overStyle||{}).length==0?{opacity:"0.8"}:null),
		...(state.mouseOver?props.overStyle:null),
		...(disabled?{opacity:"0.4"}:null)
	}
	const className = props.className	
	React.useEffect(()=>{
		props._ref && props._ref(elem.current)
		elem.current.changing = props.changing
		if(!elem.current.actions){
			elem.current.actions = {
				onEnter: e =>{
					//log(`Enter ;`)
					e.stopPropagation()					
					elem.current.click()
					const cEvent = eventManager.create(e.target)("cTab",{bubbles:true})
					elem.current.dispatchEvent(cEvent)
				},
				onClick: e =>{
					if(!props.changing && (props.onClick || props.onChange)){
						const w = e.target.ownerDocument.defaultView
						w.setTimeout(()=>(props.onClick&&props.onClick(e) || props.onChange && props.onChange({target:{headers:{"X-r-action":"change"},value:""}})),(props.delay?parseInt(props.delay):0))
					}				
					e.stopPropagation()
				}
			}
			elem.current.addEventListener("enter",elem.current.actions.onEnter)
			elem.current.addEventListener("click",elem.current.actions.onClick)				
		}
		return () =>{
			elem.current.removeEventListener("enter",elem.current.actions.onEnter)
			elem.current.removeEventListener("click",elem.current.actions.onClick)
		}
	},[])
	const onMouseOver = (value) => () => {
			if(value) props.onMouseOver && props.onMouseOver()
			else props.onMouseOut && props.onMouseOut()
			dispatch({type:'mouseOver',value})
		}
	
	const onTouchStart = (value) => () => dispatch({type:'touchStart',value})
	
	return $("button",{title:props.hint,className,key:"btn",style,ref:elem,
		onMouseOver:onMouseOver(true),onMouseOut:onMouseOver(false),
		onTouchStart:onTouchStart(true),onTouchEnd:onTouchStart(false)
		},props.children)			

}

const ButtonWithRippleElement = (props) =>{	
	const [riple,setRipple] = React.useState({effect:undefined,rBox:undefined,top:undefined,left:undefined})
	const [updateAt,setUpdateAt] = React.useState({v:0})
	const elem = React.useRef(null)
	React.useEffect(()=>{
		if(!elem.current.rippleAnim){	
			elem.current.rippleAnim = () =>{
				if(updateAt.v<=0) {
					const {width,height} = elem.current.getBoundingClientRect()					
					const rBox = Math.max(width,height)
					const top = width>height?-(rBox-height)/2:0
					const left = width<height?-(rBox-width)/2:0								
					setRipple({ripple:!riple.effect, rBox,top,left})	
					setUpdateAt({v:50})
				}
				setUpdateAt({v:updateAt.v-1})				
			}
			checkActivateCalls.add(elem.current.rippleAnim) //onMount						
		}
		return ()=>	checkActivateCalls.remove(elem.current.rippleAnim)		
	},[])
	
	const wrap = button =>{
		if(riple.top!==undefined && riple.left!==undefined && riple.rBox){					
			const anim = $("div",{key:"rp",style:{
				width:riple.rBox+"px",
				height:riple.rBox+"px",
				position:"absolute",
				top:riple.top+"px",
				left:riple.left+"px",
				backgroundColor:"transparent",
				transition:riple.effect?"transform 2.1s":"transform 0s",						
				borderRadius:"50%",						
				boxShadow: "inset 0px 0px 2.4em 0.5em rgba(255,255,255,0.9)",						
				transform:riple.effect?"scale(2,2)":"scale(0,0)",
				pointerEvents:"none"
			}})
			return $("div",{style:{position:"relative",overflow:"hidden",...props.style}},[button,anim])
		}
		else 
			return button
	}	
	return wrap($(ButtonElement,{...props,_ref:elem,style:{...props.style,margin:"0px"},key:"btn"}))
}

export {ButtonElement,ButtonWithRippleElement}