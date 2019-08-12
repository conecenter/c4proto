"use strict";
import React from 'react'
import {ButtonElement} from '../components/buttons'
const $ = React.createElement

const keyBinder = (() =>{
	const bindings = []
	const bind = (e,key,callback) =>{		
		const w = e&& e.ownerDocument.defaultView
		const fcall = (event) =>{
			if(event.key == key){
				event.preventDefault()
				callback(event)				
			}
		}
		log(e)("keyBinder",key)
		w.addEventListener("keydown",fcall,true)
		bindings.push({w,callback,fcall})				
	}
	const unbind = (callback) =>{
		const index = bindings.findIndex(_=>_.callback == callback)
		if(index>=0) {
			bindings.splice(index,1).forEach(i=>i.w.removeEventListener("keydown",i.fcall,true))
		}				
	}
	return {bind,unbind}
})()
const log  = e => {
	if(e&&e.ownerDocument&&e.ownerDocument.defaultView)
		return e.ownerDocument.defaultView.console.log
	else 
		return ()=>{}
}

const sendLogin = onChange => (event,props)=>{
	const doc = event.target.ownerDocument
	const ar = Array.from(doc.querySelectorAll(".loginDialog input"))
	if(ar.length!=2) return
	const getComputedStyle = doc.defaultView.getComputedStyle
	const body = ar.map(_=>{
		if(getComputedStyle(_).textTransform == "uppercase") return _.value.toUpperCase()
		return _.value
	}).join("\n")
	onChange({target:{headers:{"X-r-auth":"check"},value:body}})			
}
				
const TButtonElement = (props) =>{
	const {style,children,buttonCaption,className,binding, onChange} = props								
	const elem = React.useRef(null)
	React.useEffect(()=>{
		log(elem.current)("bind",binding)
		const callback = (event) =>{
			log(elem.current)("bind","do",binding)
			onChange && onChange(event,props)								
		}
		keyBinder.bind(elem.current,binding,callback)
		return () =>{
			keyBinder.unbind(callback)
			log(elem.current)("unbind",binding)	
			
		}
	},[binding])	
	const chld = children&&children.length>0?children:buttonCaption
	return $(ButtonElement,{forwardRef:elem, ...props}, chld)
}
const TLoginButtonElement = props => $(TButtonElement,{...props, onChange:sendLogin(props.onChange)})
export default {TLoginButtonElement, TButtonElement}
	