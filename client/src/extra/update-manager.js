"use strict"
import React from 'react'

export default function UpdateManager(log,window,metroUi){
	const {process,location,require,setInterval,clearInterval}  = window
	const getUpdateProgress = () => window.updateProgress
	const FlexGroup = metroUi.transforms.tp.FlexGroup
	let checked = false
	let announceOnce = false
	const ProgressBar = props => {		
		return React.createElement("div",{style:{position:"relative",height:"auto",margin:"0.625em 0.3125em 0.625em 0.3125em",backgroundColor:"#eeeeee",overflow:"hidden",boxSizing:"border-box",marginBottom:"1.5em"}},
			React.createElement("div",{style:{width:props.progress,height:"1em",float:"left",backgroundColor:"#1ba1e2"}})
		)	 		
	}
	const StatusElement = ({children}) => {
		return React.createElement("div",{style:{height:"1.4em",marginTop:"1em",marginLeft:"0.25em"},key:3},children)
	}
	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => {
			callbacks.push(c)
			const remove = () => {
				const index = callbacks.indexOf(c)
				if(index>=0)
					callbacks.splice(index,1)
			}
			return {remove}
		}		
		const check = () => callbacks.forEach(c=>c())
		return {add,check}
	})();
	const UpdaterElement = React.createClass({
		getInitialState:function(){
			return {progress:"0%"}
		},
		update:function(){
			const val = getUpdateProgress()			
			if(val!= undefined) {
				checked = false
				const prg = val + "%"				
				if(this.state.progress!=prg){
					this.setState({progress:prg})				
				}
			}
		},
		componentWillMount:function(){
			if(!process) return checked = true
			if(process && !process.execPath.includes(this.props.termApp)) return checked = true
			if(getUpdateProgress() == undefined) return checked = true
		},
		componentDidMount:function(){
			this.bind = checkActivateCalls.add(this.update)			
		},
		componentWillUnmount:function(){
			if(this.bind) this.bind.remove()
		},
		render:function(){			
			const style = {
				...this.props.style
			}
			const dialogStyle = {				
				margin:"3.5rem auto 0em auto",
				...this.props.style
			}
			if(checked) return React.createElement("div",{style},this.props.children)
			return React.createElement(FlexGroup,{style:dialogStyle,caption:""},[
				React.createElement(StatusElement,{key:1},this.props.status),
				React.createElement(ProgressBar,{key:2,progress:this.state.progress})
			])
		}
	})
	
	const transforms = {
		tp:{UpdaterElement}
	}
	const checkActivate = checkActivateCalls.check
	return {transforms,checkActivate}
}