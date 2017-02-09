"use strict";
import React from 'react'

function CustomUi(ui){
	const Chip=ui.transforms.tp.Chip;
	const TDElement=ui.transforms.tp.TDElement;
	const ConnectionState=ui.transforms.tp.ConnectionState;
	
	const StatusElement=React.createClass({
		getInitialState:function(){
			return {lit:false};
		},
		signal:function(on){
			if(on) this.setState({lit:true});
			else this.setState({lit:false});
		},
		componentDidMount:function(){
			if(window.CustomMeasurer) CustomMeasurer.regCallback(this.props.fkey.toLowerCase(),this.signal);
		},
		componentWillUnmount:function(){
			if(window.CustomMeasurer) CustomMeasurer.unregCallback(this.props.fkey.toLowerCase());
		},    
		render:function(){
			var newStyle={
				marginTop:'.6125rem',
			};
		
			return React.createElement(Chip,{style:newStyle,on:this.state.lit},this.props.fkey);
		}
	});
	const TerminalElement=React.createClass({   
		componentDidMount:function(){
			if(window.CustomTerminal)
				CustomTerminal.init(this.props.host,this.props.port,this.props.username,this.props.password,(this.props.version||0));
		},
		componentWillUnmount:function(){
			if(window.CustomTerminal)
				CustomTerminal.destroy();			
		},
		componentDidUpdate:function(prevProps, prevState){
			if(window.CustomTerminal){
				CustomTerminal.destroy(this.props.version);				
				CustomTerminal.init(this.props.host,this.props.port,this.props.username,this.props.password,this.props.version);
				console.log("reinit term");
			}
		},
		render:function(){
			var style={
				fontSize:'0.8rem',
			};
			if(this.props.style)
				Object.assign(style,this.props.style);
			return React.createElement("div",{id:'terminal',version:this.props.version,style:style},null);
		},
	});
	const MJobCell = React.createClass({
		getInitialState:function(){
			return ({data:null,version:0});
		},
		signal:function(data){
			//const gData=(data!=undefined&&parseInt(data)>=0?data:null);
			const gData=(data?data:null);			
			this.setState({data:gData});			
		},
		componentDidMount:function(){
			if(window.CustomMeasurer)
				CustomMeasurer.regCallback(this.props.fkey,this.signal);        
		},
		componentWillUnmount:function(){
			if(window.CustomMeasurer)
				CustomMeasurer.unregCallback(this.props.fkey);
		},
		onChange:function(e){
			if(this.props.onChange)
				this.props.onChange(e);
		},
		componentWillReceiveProps:function(nextProps){
			if(nextProps.init&&nextProps.init!=this.props.init)
				this.setState({data:null});
		},
		onClick:function(e){
			if(this.props.onClick)
				this.props.onClick(e);
		},
		render:function(){
			var style={
				minWidth:'2rem',
			};
			const inpStyle={
				border:'none',
				fontSize:'1.7rem',
				width:'100%',
				backgroundColor:'inherit',
				padding:'0px',
				margin:'0px',
				flexBasis:'7rem',
				flexGrow:'1',
			};
			Object.assign(style,this.props.style);		
			const statusText = (this.props.statusText?this.props.statusText:"");
			
			return React.createElement(TDElement,{key:"wEl",odd:this.props.odd,style},[
				React.createElement(ControlledComparator,{key:"1",onChange:this.onChange,data:this.state.data},null),
				React.createElement('div',{key:"2",style:{display:'flex',flexWrap:'noWrap'}},[				
					React.createElement("input",{type:"text",readOnly:"readonly",key:"3",style:inpStyle,value:statusText},null),
					(this.props.time&&this.props.time.length>0?
					React.createElement("span",{style:{key:"time",marginRight:"1rem",lineHeight:"1"}},this.props.time):null),
					//(this.state.data!=null?
					//React.createElement(GotoButton,{key:"2",onClick:this.onClick,style:this.props.buttonStyle,overStyle:this.props.buttonOverStyle},buttonText):null),
				]),
			]);
		},
	});

	const ControlledComparator = React.createClass({
		componentDidUpdate:function(prevP,prevS){
			if(this.props.onChange&&this.props.data&&prevP.data!==this.props.data){			
				const e={target:{value:this.props.data}};
				console.log("change w");
				this.props.onChange(e);
			}
		},
		render:function(){		
			//const value = this.props.data!=null?this.props.data:"";
			return React.createElement('span',{key:"1"},null);
		},
	});
	const IconCheck = React.createClass({
		render:function(){
			var style={};
			Object.assign(style,this.props.style);
			const imageSvg ='<svg version="1.1"  xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 30 30"><g><path fill="#3C763D" d="M22.553,7.684c-4.756,3.671-8.641,7.934-11.881,12.924c-0.938-1.259-1.843-2.539-2.837-3.756 C6.433,15.13,4.027,17.592,5.419,19.3c1.465,1.795,2.737,3.734,4.202,5.529c0.717,0.88,2.161,0.538,2.685-0.35 c3.175-5.379,7.04-9.999,11.973-13.806C26.007,9.339,24.307,6.33,22.553,7.684z"/></g></svg>';
			const imageSvgData = "data:image/svg+xml;base64,"+window.btoa(imageSvg);
			return React.createElement("img",{style,src:imageSvgData},null);
		},
	});
	
	const PingReceiver = function(){
		let pingerTimeout=null;
		let callbacks=[];
		function ping(){			
			if(pingerTimeout){clearTimeout(pingerTimeout); pingerTimeout = null;}
			if(!callbacks.length) return;
			pingerTimeout=setTimeout(function(){callbacks.forEach((o)=>o.func(false));},5000);
			callbacks.forEach((o)=>o.func(true));
		};		
		function regCallback(func,obj){
			callbacks.push({obj,func});
		};
		function unregCallback(obj){
			callbacks=callbacks.filter((o)=>o.obj!=obj);
		};
		return {ping,regCallback,unregCallback};
	}();	
	
	const DeviceConnectionState = React.createClass({
		getInitialState:function(){
			return {on:true};
		},
		signal:function(on){			
			if(this.state.on!=on)
				this.setState({on});
		},
		componentDidMount:function(){					
			if(PingReceiver)
				PingReceiver.regCallback(this.signal,this);
			this.toggleOverlay(!this.state.on);			
		},
		componentWillUnmount:function(){
			if(PingReceiver)
				PingReceiver.unregCallback(this);
		},
		toggleOverlay:function(on){
			if(!this.props.overlay) return;
			if(on){
				const el=document.createElement("div");
				const style={
					position:"fixed",
					top:"0rem",
					left:"0rem",
					width:"100vw",
					height:"100vh",
					backgroundColor:"rgba(0,0,0,0.4)",					
				};
				el.className="overlayMain";
				Object.assign(el.style,style);
				document.body.appendChild(el);
			}
			else{
				const el=document.querySelector(".overlayMain");
				if(el)	document.body.removeChild(el);
			}
		},
		componentDidUpdate:function(prevProps,prevState){			
			this.toggleOverlay(!this.state.on);			
		},
		render:function(){
			var style={				
			};
			var iconStyle={				
			};
			if(this.props.style) Object.assign(style,this.props.style);
			if(this.props.iconStyle) Object.assign(iconStyle,this.props.iconStyle);			
			
			return React.createElement(ConnectionState,{on:this.state.on,style:style,iconStyle:iconStyle}, null);			
		},
	});
	const CustomMeasurerConnectionState = React.createClass({
		getInitialState:function(){
			return {on:false};
		},
		signal:function(on){
			if(this.state.on!=on)
				this.setState({on});
		},
		componentDidMount:function(){					
			if(CustomMeasurer)
				CustomMeasurer.regCallback(this.props.fkey,this.signal);			
		},
		componentWillUnmount:function(){
			if(CustomMeasurer)
				CustomMeasurer.unregCallback(this.props.fkey);
		},
		render:function(){
			var style ={};
			var iconStyle ={};
			if(this.props.style) Object.assign(style,this.props.style);
			if(this.props.iconStyle) Object.assign(iconStyle,this.props.iconStyle);	
			return React.createElement(ConnectionState,{on:this.state.on,style,iconStyle});
		},
	});
	const transforms= {
		tp:{
			StatusElement,TerminalElement,MJobCell,IconCheck,CustomMeasurerConnectionState,DeviceConnectionState,
		},
	};
	const receivers = {
		ping:PingReceiver.ping
	};
	return {transforms,receivers};
}

export default CustomUi