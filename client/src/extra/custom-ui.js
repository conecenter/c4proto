"use strict";
import React from 'react'

export default function CustomUi({log,ui,customMeasurer,customTerminal,svgSrc,setTimeout,clearTimeout,toggleOverlay}){
	const Chip=ui.transforms.tp.Chip;
	const TDElement=ui.transforms.tp.TDElement;
	
	const StatusElement=React.createClass({
		getInitialState:function(){
			return {lit:false};
		},
		signal:function(on){
			if(on) this.setState({lit:true});
			else this.setState({lit:false});
		},
		componentDidMount:function(){
			customMeasurer().forEach(m=>m.regCallback(this.props.fkey.toLowerCase(),this.signal));
		},
		componentWillUnmount:function(){
			customMeasurer().forEach(m=>m.unregCallback(this.props.fkey.toLowerCase()));
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
			customTerminal().forEach(t=>t.init(this.props.host,this.props.port,this.props.username,this.props.password,(this.props.version||0)));
		},
		componentWillUnmount:function(){
			customTerminal().forEach(t=>t.destroy());
		},
		componentDidUpdate:function(prevProps, prevState){
			customTerminal().forEach(t=>{
				t.destroy(this.props.version);
				t.init(this.props.host,this.props.port,this.props.username,this.props.password,this.props.version);
				log("reinit term");
			})
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
			customMeasurer().forEach(m=>m.regCallback(this.props.fkey,this.signal));
		},
		componentWillUnmount:function(){
			customMeasurer().forEach(m=>m.unregCallback(this.props.fkey));
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
				log("change w");
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
			const imageSvgData = svgSrc(imageSvg);
			return React.createElement("img",{style,src:imageSvgData},null);
		},
	});
	
	const PingReceiver = function(){
		let pingerTimeout=null;
		let callback=null;
		function ping(){			
			if(pingerTimeout){clearTimeout(pingerTimeout); pingerTimeout = null;}
			if(!callback) return;
			pingerTimeout=setTimeout(function(){if(callback) callback(false);},10000);
			callback(true);
		};		
		function regCallback(func){
			callback=func;
		};
		function unregCallback(){
			callback=null;
		};
		return {ping,regCallback,unregCallback};
	}();
	const ConnectionState = React.createClass({
		getInitialState:function(){
			return {on:false};
		},
		signal:function(on){			
			if(this.state.on!=on)
				this.setState({on});
		},
		componentDidMount:function(){					
			if(PingReceiver)
				PingReceiver.regCallback(this.signal);
			this.toggleOverlay(!this.state.on);			
		},
		componentWillUnmount:function(){
			if(PingReceiver)
				PingReceiver.unregCallback();
		},
		toggleOverlay:function(on){
			if(!this.props.overlay) return;
			toggleOverlay(on)
		},
		componentDidUpdate:function(prevProps,prevState){			
			this.toggleOverlay(!this.state.on);			
		},
		render:function(){
			var style={
				borderRadius:"1rem",
				border:"0.1rem solid black",
				backgroundColor:this.state.on?"green":"red",		
				display:'inline-block',
			};
			var iconStyle={
				position:'relative',
				top:'-10%',
			};
			Object.assign(style,this.props.style);
			Object.assign(iconStyle,this.props.iconStyle);
			const imageSvg='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 285.269 285.269" style="enable-background:new 0 0 285.269 285.269;" xml:space="preserve"> <path style="fill:black;" d="M272.867,198.634h-38.246c-0.333,0-0.659,0.083-0.986,0.108c-1.298-5.808-6.486-10.108-12.679-10.108 h-68.369c-7.168,0-13.318,5.589-13.318,12.757v19.243H61.553C44.154,220.634,30,206.66,30,189.262 c0-17.398,14.154-31.464,31.545-31.464l130.218,0.112c33.941,0,61.554-27.697,61.554-61.637s-27.613-61.638-61.554-61.638h-44.494 V14.67c0-7.168-5.483-13.035-12.651-13.035h-68.37c-6.193,0-11.381,4.3-12.679,10.108c-0.326-0.025-0.653-0.108-0.985-0.108H14.336 c-7.168,0-13.067,5.982-13.067,13.15v48.978c0,7.168,5.899,12.872,13.067,12.872h38.247c0.333,0,0.659-0.083,0.985-0.107 c1.298,5.808,6.486,10.107,12.679,10.107h68.37c7.168,0,12.651-5.589,12.651-12.757V64.634h44.494 c17.398,0,31.554,14.262,31.554,31.661c0,17.398-14.155,31.606-31.546,31.606l-130.218-0.04C27.612,127.862,0,155.308,0,189.248 s27.612,61.386,61.553,61.386h77.716v19.965c0,7.168,6.15,13.035,13.318,13.035h68.369c6.193,0,11.381-4.3,12.679-10.108 c0.327,0.025,0.653,0.108,0.986,0.108h38.246c7.168,0,12.401-5.982,12.401-13.15v-48.977 C285.269,204.338,280.035,198.634,272.867,198.634z M43.269,71.634h-24v-15h24V71.634z M43.269,41.634h-24v-15h24V41.634z M267.269,258.634h-24v-15h24V258.634z M267.269,228.634h-24v-15h24V228.634z"/></svg>';
			const imageSvgData = svgSrc(imageSvg);
			
			return React.createElement("div",{style:style},
				React.createElement("img",{key:"1",style:iconStyle,src:imageSvgData},null)
				);
			
		},
	});
	const transforms= {
		tp:{
		StatusElement,TerminalElement,MJobCell,IconCheck,ConnectionState	
		},
	};
	const receivers = {
		ping:PingReceiver.ping
	};
	return {transforms,receivers};
}
