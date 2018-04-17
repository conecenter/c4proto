"use strict";
import React from 'react'
import {rootCtx} from "../main/vdom-util"

export default function CustomUi({log,ui,requestState,customMeasurer,customTerminal,svgSrc,overlayManager,getBattery,scannerProxy,windowManager,winWifi,miscReact,StatefulComponent}){
	const {setTimeout,clearTimeout} = windowManager

	const ChipElement=ui.transforms.tp.ChipElement;
	const TDElement=ui.transforms.tp.TDElement;
	const ConnectionState=ui.transforms.tp.ConnectionState;
	
	class StatusElement extends StatefulComponent{		
		getInitialState(){return {lit:false}}
		signal(on){
			if(this.props.onChange)
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:on.toString()}})
			if(on) this.setState({lit:true});
			else this.setState({lit:false});
			
		}
		shouldComponentUpdate(nextProps, nextState){
			if(customMeasurer().length>0 && nextProps.lit!=nextState.lit) return false
			return true
		}
		onClick(){
			customMeasurer().forEach(m=>m._do(this.props.fkey.toLowerCase()))
		}
		componentDidMount(){			
			customMeasurer().forEach(m=>m.regCallback(this.props.fkey.toLowerCase(),this.signal));
		}
		componentWillUnmount(){
			customMeasurer().forEach(m=>m.unregCallback(this.props.fkey.toLowerCase()));
		} 
		render(){
			const backgroundColor = customMeasurer().length>0?(this.state.lit?'#ffa500':'#eeeeee'):(this.props.lit?'#ffa500':'#eeeeee')
			const borderColor = customMeasurer().length>0?(this.state.lit?'#ffa500':'#eeeeee'):(this.props.lit?'#ffa500':'#eeeeee')
			const style={
				marginTop:'.6125em',
				...this.props.style,
				backgroundColor,
				borderColor				
			};
		
			return React.createElement(ChipElement,{style,onClick:this.onClick,value:this.props.fkey});
		}
	}
	class TerminalElement extends StatefulComponent{   
		componentDidMount(){
			customTerminal().forEach(t=>t.init(this.props.host,this.props.port,this.props.username,this.props.password,(this.props.params||0),this.props.wrk,this.props.ps));
			log("term mount")
		}
		componentWillUnmount(){
			customTerminal().forEach(t=>t.destroy());
			log("term unmount")
		}
		componentDidUpdate(prevProps, prevState){
			customTerminal().forEach(t=>{
				log("term_update")
				if(prevProps.version!=this.props.version&&this.props.version!=0){
					t.destroy(-1);
					t.init(this.props.host,this.props.port,this.props.username,this.props.password,this.props.params,this.props.wrk,this.props.ps);
				}
			})
		}
		render(){				
			const style = {
				backgroundColor:"black",
				...this.props.style
			}
			return React.createElement("div",{className:'terminalElement',version:this.props.version,style},
				React.createElement("div",{style:{color:"white", position:"absolute"}}, "Client Private Terminal")
				)
		}
	}
	class MJobCell extends StatefulComponent{		
		getInitialState(){return {data:null,version:0}}
		signal(data){
			//const gData=(data!=undefined&&parseInt(data)>=0?data:null);
			const gData=(data?data:null);			
			this.setState({data:gData});			
		}
		componentDidMount(){
			customMeasurer().forEach(m=>m.regCallback(this.props.fkey,this.signal));
		}
		componentWillUnmount(){
			customMeasurer().forEach(m=>m.unregCallback(this.props.fkey));
		}
		onChange(e){
			if(this.props.onChange)
				this.props.onChange(e);
		}
		componentWillReceiveProps(nextProps){
			if(nextProps.init&&nextProps.init!=this.props.init)
				this.setState({data:null});
		}
		onClick(e){
			if(this.props.onClick)
				this.props.onClick(e);
		}
		render(){
			const style={
				minWidth:'2rem',
				...this.props.style
			};
			const inpStyle={
				border:'none',
				fontSize:'inherit',
				width:'100%',
				backgroundColor:'inherit',
				padding:'0px',
				margin:'0px',
				flexBasis:'7rem',
				flexGrow:'1',
			};				
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
		}
	}

	class ControlledComparator extends StatefulComponent{
		componentDidUpdate(prevP,prevS){
			if(this.props.onChange&&this.props.data&&prevP.data!==this.props.data){			
				const e={target:{headers:{"X-r-action":"change"},value:this.props.data.toString()}};
				log("change w");
				this.props.onChange(e);
			}
		}
		render(){		
			//const value = this.props.data!=null?this.props.data:"";
			return React.createElement('span',{key:"1"},null);
		}
	}
	const IconCheck = ({style})=>{
		const imageSvg ='<svg version="1.1"  xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 30 30"><g><path fill="#3C763D" d="M22.553,7.684c-4.756,3.671-8.641,7.934-11.881,12.924c-0.938-1.259-1.843-2.539-2.837-3.756 C6.433,15.13,4.027,17.592,5.419,19.3c1.465,1.795,2.737,3.734,4.202,5.529c0.717,0.88,2.161,0.538,2.685-0.35 c3.175-5.379,7.04-9.999,11.973-13.806C26.007,9.339,24.307,6.33,22.553,7.684z"/></g></svg>';
		const imageSvgData = svgSrc(imageSvg);
		return React.createElement("img",{style:style,src:imageSvgData},null);		
	};	
	const PingReceiver = function(){
		let pingerTimeout=null;
		let callbacks=[];
		function ping(data){			
			if(pingerTimeout){clearTimeout(pingerTimeout); pingerTimeout = null;}
			if(!callbacks.length) return;
			pingerTimeout=setTimeout(function(){callbacks.forEach((o)=>o.func(false,null));},5000);
			callbacks.forEach((o)=>o.func(true,null));
		};		
		function regCallback(func,obj){
			callbacks.push({obj,func});
		};
		function unregCallback(obj){
			callbacks=callbacks.filter((o)=>o.obj!=obj);
		};
		return {ping,regCallback,unregCallback};
	}();	
	let prevWifiLevel = null
	let wifiData = null
	class DeviceConnectionState extends StatefulComponent{		
		getInitialState(){return {on:true,wifiLevel:null,waiting:null,data:null,showWifiInfo:false}}
		signal(on,data){			
			if(this.state.on!=on)
				this.setState({on});
			if(this.state.data!=data)
				this.setState({data})
		}
		wifiCallback(wifiLevel,plain){
			prevWifiLevel = wifiLevel
			wifiData = plain
			if(this.state.wifiLevel != wifiLevel){				
				this.setState({wifiLevel})
			}
		}
		yellowSignal(on){
			if(this.state.waiting!=on)
				this.setState({waiting:on})
		}
		componentDidMount(){
			const count = miscReact.count()
			if(count>1) return
			if(PingReceiver)
				PingReceiver.regCallback(this.signal,this);
			this.toggleOverlay(!this.state.on);			
			this.wifi = scannerProxy.regWifi(this.wifiCallback)
			this.wifi2 = winWifi.regWifi(this.wifiCallback)
			if(this.props.onContext && requestState.reg){
				const branchKey = this.props.onContext()
				this.yellow = requestState.reg({branchKey,callback:this.yellowSignal})
			}
		}
		componentWillUnmount(){			
			if(PingReceiver)
				PingReceiver.unregCallback(this);
			if(this.wifi) this.wifi.unreg();
			if(this.wifi2) this.wifi2.unreg();
			if(this.yellow) this.yellow.unreg();
		}		
		toggleOverlay(on){
			if(!this.props.overlay) return;
			if(this.props.msg||this.state.waiting) 
				overlayManager.delayToggle(this.props.msg||this.state.waiting)
			else
				overlayManager.toggle(on)
			
		}
		componentDidUpdate(prevProps,prevState){
			if(prevState.on != this.state.on){
				log(`toggle ${this.state.on}`)
				this.toggleOverlay(!this.state.on);
			}
		}
		onMouseOver(){			
			this.setState({showWifiInfo:true});
		}
		onMouseOut(){
			if(this.state.showWifiInfo)
				this.setState({showWifiInfo:false});
		}
		render(){
			const wifiLevel = prevWifiLevel&&!this.state.wifiLevel?prevWifiLevel:this.state.wifiLevel
			const wifiStyle = wifiLevel!==null?{padding:"0.11em 0em"}:{}
			const wifiIconStyle = wifiLevel!==null?{width:"0.7em"}:{}
			const waitingStyle = (this.props.msg || this.state.waiting)?{backgroundColor:"yellow",color:"rgb(114, 114, 114)"}:{}
			const style={
				color:"white",
				textAlign:"center",
				...waitingStyle,
				...wifiStyle,
				...this.props.style
			};
			const iconStyle={
				...wifiIconStyle
			};
			if(this.props.style) Object.assign(style,this.props.style);
			if(this.props.iconStyle) Object.assign(iconStyle,this.props.iconStyle);
			let imageSvgData = null;
			if(wifiLevel !== null){ //0 - 4
				const wL = parseInt(wifiLevel)
				const getColor = (cond) => cond?"white":"transparent"
				const l4C = getColor(wL>=4)
				const l3C = getColor(wL>=3)
				const l2C = getColor(wL>=2)
				const l1C = getColor(wL>=1)
				const wifiSvg = `
				<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" x="0px" y="0px" viewBox="0 0 147.586 147.586" style="enable-background:new 0 0 147.586 147.586;" xml:space="preserve">
					<path style="stroke:white;fill: ${l2C};" d="M48.712,94.208c-2.929,2.929-2.929,7.678,0,10.606c2.93,2.929,7.678,2.929,10.607,0   c7.98-7.98,20.967-7.98,28.947,0c1.465,1.464,3.385,2.197,5.304,2.197s3.839-0.732,5.304-2.197c2.929-2.929,2.929-7.678,0-10.606   C85.044,80.378,62.542,80.378,48.712,94.208z"></path>
					<path style="stroke:white;fill: ${l3C};" d="M26.73,72.225c-2.929,2.929-2.929,7.678,0,10.606s7.677,2.93,10.607,0   c20.102-20.102,52.811-20.102,72.912,0c1.465,1.464,3.385,2.197,5.304,2.197s3.839-0.732,5.304-2.197   c2.929-2.929,2.929-7.678,0-10.606C94.906,46.275,52.681,46.275,26.73,72.225z"></path>
					<path style="stroke:white;fill: ${l4C};" d="M145.39,47.692c-39.479-39.479-103.715-39.479-143.193,0c-2.929,2.929-2.929,7.678,0,10.606   c2.93,2.929,7.678,2.929,10.607,0c16.29-16.291,37.95-25.262,60.989-25.262s44.699,8.972,60.989,25.262   c1.465,1.464,3.385,2.197,5.304,2.197s3.839-0.732,5.304-2.197C148.319,55.37,148.319,50.621,145.39,47.692z"></path>
					<circle style="stroke:white;fill: ${l1C};" cx="73.793" cy="121.272" r="8.231"></circle>
				</svg>`;
				imageSvgData = svgSrc(wifiSvg)				
			}	
			const wifiDataEl = this.state.showWifiInfo&&wifiData?React.createElement("pre",{style:{
				position:"absolute",
				marginTop:"2.7em",
				width:"40em",
				fontSize:"12px",
				zIndex:"1000",
				backgroundColor:"blue",
				right:"0.24em",
				textAlign:"left",
				color:"white"
			},key:"2"},wifiData):null;
			const on = (this.props.on === false)? false: this.state.on
			return React.createElement("div",{style:{display:"flex"},onMouseEnter:this.onMouseOver,onMouseLeave:this.onMouseOut},[
				React.createElement(ConnectionState,{key:"1",onClick:this.props.onClick,on,style:style,iconStyle:iconStyle,imageSvgData}, null),
				wifiDataEl,
				React.createElement("span",{style:{fontSize:"10px",alignSelf:"center"},key:"3"},this.state.data)
			])
		}
	}
	class CustomMeasurerConnectionState extends StatefulComponent{		
		getInitialState(){return {on:false}}
		signal(on){
			if(this.state.on!=on)
				this.setState({on});
		}
		componentDidMount(){					
			customMeasurer().forEach(m=>m.regCallback(this.props.fkey,this.signal));
		}
		componentWillUnmount(){
			customMeasurer().forEach(m=>m.unregCallback(this.props.fkey));
		}
		render(){
			var style ={};
			var iconStyle ={};
			if(this.props.style) Object.assign(style,this.props.style);
			if(this.props.iconStyle) Object.assign(iconStyle,this.props.iconStyle);	
			return React.createElement(ConnectionState,{on:this.state.on,style,iconStyle});
		}
	}
	class BatteryState extends StatefulComponent{		
		getInitialState(){return {batteryLevel:0,isCharging:false}}
		onLevelChange(){
			if(!this.battery) return;
			this.setState({batteryLevel:this.battery.level});
		}
		onChargingChange(){
			if(!this.battery) return;
			this.setState({isCharging:this.battery.charging});
		}
		onBatteryInit(battery){
			this.battery = battery;
			this.battery.addEventListener("chargingchange",this.onChargingChange);
			this.battery.addEventListener("levelchange",this.onLevelChange);
			this.setState({batteryLevel:this.battery.level,isCharging:this.battery.charging});
		}
		componentDidMount(){
			getBattery&&getBattery(this.onBatteryInit);
		}		
		componentWillUnmount(){
			if(!this.battery) return;
			this.battery.removeEventListener("charginchange",this.onChargingChange);
			this.battery.removeEventListener("levelchange",this.onLevelChange);
		}
		render(){
			const style={
				display:"flex",				
				marginLeft:"0.2em",
				marginRight:"0.2em",
				alignSelf:"center",
				...this.props.style
			};
			const svgStyle = {				
				height:"1em"				
			};
			const textStyle={
				fontSize:"0.5em",
				alignSelf:"center"
				//verticalAlign:"middle",
			};
			const svgImgStyle = {
				enableBackground:"new 0 0 60 60",
				verticalAlign:"top",
				height:"100%"
			}
			
			const statusColor = this.state.batteryLevel>0.2?"green":"red";
			const batteryLevel = Math.round(this.state.batteryLevel*100);
			const el=React.createElement("div",{style},[
					React.createElement("span",{key:"2",style:textStyle},batteryLevel + "%"),
					React.createElement("div",{key:"1",style:svgStyle},
						React.createElement("svg",{
							key:"1",
							xmlns:"http://www.w3.org/2000/svg", 
							xmlnsXlink:"http://www.w3.org/1999/xlink",
							version:"1.1",
							x:"0px",
							y:"0px",
							viewBox:"0 0 60 60",
							style:svgImgStyle,
							xmlSpace:"preserve"},[
								React.createElement("path",{key:"1",fill:"white",stroke:"white",d:"M42.536,4 H36V0H24 v4h-6.536 C15.554,4,14,5.554,14,7.464 v49.07 2C14,58.446,15.554,60,17.464,60 h25.071   C44.446,60,46,58.446,46,56.536 V7.464 C46,5.554,44.446,4,42.536,4z M44,56.536 C44,57.344,43.343,58,42.536,58 H17.464   C16.657,58,16,57.344,16,56.536V7.464C16,6.656,16.657,6,17.464,6H24h12h6.536C43.343,6,44,6.656,44,7.464V56.536z"},null),
								React.createElement("rect",{
									key:"_2",
									fill:"white",
									x:"15.4",
									y:5.2 +"",
									width:"28.8",
									height:(52.6 - this.state.batteryLevel*52.6)+""
								},null),
								React.createElement("rect",{
									key:"_1",
									fill:statusColor,
									x:"15.4",
									y:5.2 + (52.6 - this.state.batteryLevel*52.6)+"",
									width:"28.8",
									height:(this.state.batteryLevel*52.6)+""
								},null),
								React.createElement("path",{key:"2",fill:(this.state.isCharging?"rgb(33, 150, 243)":"transparent"),d:"M37,29h-3V17.108c0.013-0.26-0.069-0.515-0.236-0.72c-0.381-0.467-1.264-0.463-1.642,0.004   c-0.026,0.032-0.05,0.066-0.072,0.103L22.15,32.474c-0.191,0.309-0.2,0.696-0.023,1.013C22.303,33.804,22.637,34,23,34h4   l0.002,12.929h0.001c0.001,0.235,0.077,0.479,0.215,0.657C27.407,47.833,27.747,48,28.058,48c0.305,0,0.636-0.16,0.825-0.398   c0.04-0.05,0.074-0.103,0.104-0.159l8.899-16.979c0.163-0.31,0.151-0.682-0.03-0.981S37.35,29,37,29z"},null),
							]
						)
					)					
				]);
			return getBattery?el:null;
		}
	}
	class ScannerProxyElement extends StatefulComponent{		
		callback(type,data){
			if(this.props.onClickValue)
				this.props.onClickValue(type,data)
		}
		scanMode(){
			return this.props.scanMode
		}
		componentDidMount(){
			if(this.props.barcodeReader)
				this.binding = scannerProxy.reg(this)
		}
		componentDidUpdate(prevProps,_){
			if(this.props.barcodeReader && this.props.scanMode!=prevProps.scanMode && this.binding){
				this.binding.switchTo(this.props.scanMode)
				return
			}
			if(prevProps.barcodeReader != this.props.barcodeReader){
				if(this.props.barcodeReader && !this.binding) this.binding = scannerProxy.reg(this)
				else if(!this.props.barcodeReader && this.binding) this.binding.unreg()
			}		
			
		}
		componentWillUnmount(){
			this.binding&&this.binding.unreg()
		}
		render(){
			return React.createElement("span");
		}
	}
	const ctx = ctx => () =>{
		return rootCtx(ctx).branchKey
	}
	const onContext = ({ctx})
	const transforms= {
		tp:{
			StatusElement,TerminalElement,MJobCell,IconCheck,CustomMeasurerConnectionState,DeviceConnectionState,			
			BatteryState,ScannerProxyElement			
		},
		onContext
	};
	const receivers = {
		ping:PingReceiver.ping		
	};	
	return {transforms,receivers};
}
