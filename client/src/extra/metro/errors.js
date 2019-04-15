"use strict";
import React 	from 'react'
import ReactDOM from 'react-dom'
import Images from "./media/images.js"
import {ButtonElement} from './components/buttons.js'
const $ = React.createElement
let images,errors
const Errors = (document) =>{
	const callbacks = [];
	let tmpNode = null;	
	const $ = React.createElement	
	const reg = (callback) => {
		callbacks.push(callback)
		const unreg = ()=> {
			const index = callbacks.findIndex(c => c == callback)
			callbacks.splice(index,1)
		}
		return {unreg}
	}
	const fail = (data)=> {
		if(callbacks.length>0)
			callbacks.forEach(c=>c(data))
		else
			showError(data)
	}
	const showError = (data) => {		
		tmpNode = document.createElement("div")
		tmpNode.className="ouch"
		tmpNode.style.position="absolute"
		tmpNode.style.width="100%"
		tmpNode.style.zIndex="6667"
		document.body.insertBefore(tmpNode,document.body.firstChild)
		const onClick = () => {
			ReactDOM.unmountComponentAtNode(tmpNode)
			tmpNode.parentElement.removeChild(tmpNode)
			tmpNode = null
		}				
		const rootErrorElement = $(ErrorElement,{onClick,data})
		ReactDOM.render(rootErrorElement,tmpNode)
	}
	const receivers = {fail};	
	return {receivers,reg};
}

const ErrorElement = (props) => {
	const elem = React.useRef(null)
	const [state,setError] = React.useState({show:false,data:null})	
	const onClick = (e) =>{			
		setError({show:false,data:null})
		if(props.onClick) props.onClick(e)
	}	
	React.useEffect(()=>{
		if(!images) images = Images(elem.current.ownerDocument.defaultView.btoa)
		if(!errors) errors = Errors(elem.current.ownerDocument)
		if(!elem.current.binding) elem.current.binding = errors.reg((data)=>setError({show:true,data}))
		return () => elem.current.binding && elem.current.binding.unreg()
	},[])
	if(state.show||props.data!=undefined){				
		const closeImg = $("img",{src:images.closeSvgData,style:{width:"1.5em",display:"inherit",height:"0.7em"}})
		const noteImg = $("img",{src:images.noteSvgData,style:{width:"1.5em",display:"inherit"}})
		const data = props.data?props.data:state.data
		const buttonEls = props.onClick?[					
				$(ButtonElement,{key:"but2",onClick,style:{/*margin:"5mm",*/margin:"0px",flex:"0 0 auto"}},closeImg)
			]:null
		const style = {
			backgroundColor:"white",
			padding:"0em 1.25em",
			borderTop:"0.1em solid #1976d2",
			borderBottom:"0.1em solid #1976d2",
			...props.style
		}	
		const errorEl = $("div",{style, ref:elem},
			$("div",{style:{display:"flex",height:"auto",margin:"0.2em"}},[
				$("div",{key:"msg",style:{display:"flex",flex:"1 1 auto",minWidth:"0"}},[
					$("div",{key:"icon",style:{alignSelf:"center"}},noteImg),
					$("div",{key:"msg",style:{alignSelf:"center",color:"red",flex:"0 1 auto",margin:"0em 0.5em",overflow:"hidden",textOverflow:"ellipsis"}},data)						
				]),
				buttonEls
			])
		)			
		return errorEl
	}	
	else 
		return null
	
}
export {ErrorElement,Errors}

