"use strict";
import React from 'react'
export default function CustomUi({log,ui,customMeasurer,customTerminal,miscReact,miscUtil,StatefulComponent}){
	const {ChipElement,ConnectionState,ButtonElement,ControlWrapperElement} = ui.transforms.tp	
	const $ = React.createElement
	const DarkPrimaryColor = "#1976d2"
	const PrimaryColor = "#2196f3"
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
		
			return $(ChipElement,{style,onClick:this.onClick,value:this.props.fkey});
		}
	}
	class TerminalElement extends StatefulComponent{  
		getInitialState(){
			return {version:0}
		}
		componentDidMount(){
			if(!this.props.host||!this.el) return
			customTerminal().forEach(t=>t.init(this.props.host.split(":")[0],this.props.host.split(":")[1],this.props.username,this.props.password,(this.props.params||0),this.props.wrk,this.props.ps));
			log("term mount")
			this.le= this.el.ownerDocument.defaultView.logoutTrigger && this.el.ownerDocument.defaultView.logoutTrigger.reg(this.el,this.reset)
		}
		reset(){
			this.setState({version:this.state.version+1})
		}
		componentWillUnmount(){
			if(!this.props.host) return
			customTerminal().forEach(t=>t.destroy());
			log("term unmount")
			this.le && this.le.unreg()
		}
		componentDidUpdate(prevProps, prevState){
			if(!this.props.host) return
			customTerminal().forEach(t=>{
				log("term_update")
				if(
					prevProps.version!=this.props.version&&this.props.version!=0 ||
					prevState.version!=this.state.version&&this.state.version!=0
					)
				{
					t.destroy(-1);
					t.init(this.props.host.split(":")[0],this.props.host.split(":")[1],this.props.username,this.props.password,this.props.params,this.props.wrk,this.props.ps);
				}
			})
		}
		render(){				
			const style = {
				backgroundColor:"black",
				...this.props.style
			}
			const className = !this.props.host?"dummyTerminal":"terminalElement"
			return $(React.Fragment,{},[
				$("div",{key:"term",ref:ref=>this.el=ref,className:className,version:this.props.version,style},
					$("div",{style:{color:"white", position:"absolute"}}, "Client Private Terminal")
				),
				$(ControlWrapperElement,{key:"btn",style:{alignSelf:"center",display:"inline-block",outlineWidth:"0.1em",padding:"0em",width:"auto"}},
					$(ButtonElement,{key:"btn",onClick:this.reset,
						overStyle:{backgroundColor:DarkPrimaryColor,color:"white"},
						style:{backgroundColor:PrimaryColor,color:"white",fontSize:"1.5em",position:"relative",zIndex:"1"}
						},"Reconnect")
				)
			])
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
		/*onClick(e){
			if(this.props.onClick)
				this.props.onClick(e);
		}*/
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
			
			return $("div",{key:"wEl",odd:this.props.odd,style},[
				$(ControlledComparator,{key:"1",onChange:this.onChange,data:this.state.data},null),
				$('div',{key:"2",style:{display:'flex',flexWrap:'noWrap'}},[				
					$("input",{type:"text",readOnly:"readonly",key:"3",style:inpStyle,value:statusText},null),
					(this.props.time&&this.props.time.length>0?
					$("span",{style:{key:"time",marginRight:"1rem",lineHeight:"1"}},this.props.time):null),
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
			return $('span',{key:"1"},null);
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
			return $(ConnectionState,{on:this.state.on,style,iconStyle});
		}
	}

    class OCRScannerElement extends StatefulComponent{
		callback(value){
            if(this.props.onClickValue && !this.unmount)
                this.props.onClickValue("change", value)
		}
		onclick(){
			if(this.el === null) return;
            const wind =  this.el.el.ownerDocument.defaultView;
			if(wind.scanImage)
                wind.scanImage(this.props.imgPath, this.props.fromObject,this.callback);
		}
        componentDidMount(){
            this.onclick();
        }

        componentWillUnmount(){
            this.unmount = true;
        }

        render(){
            return $(ButtonElement, {onClick:this.onclick, ref:ref=>this.el=ref}, this.props.value);
        }
    }

    class ScannerProxyElement extends StatefulComponent{
		getInitialState(){
			return {status:false}
		}
		callback(type,data){
			if(this.props.onClickValue)
				this.props.onClickValue(type,data)
		}
		scanMode(){
			return this.props.scanMode
		}
		componentDidMount(){
			if(this.props.barcodeReader){
				this.binding = miscUtil.scannerProxy().reg(this)
				const status =  this.binding.status()
				if(status!=this.state.status) this.setState({status})
			}
		}
		componentDidUpdate(prevProps,_){
			if(this.binding && this.state.status != this.binding.status()){
				return this.setState({status:!this.state.status})
			}			
			if(this.props.barcodeReader && this.props.scanMode!=prevProps.scanMode && this.binding){
				this.binding.switchTo(this.props.scanMode)
				return
			}
			if(prevProps.barcodeReader != this.props.barcodeReader){
				if(this.props.barcodeReader && !this.binding) this.binding = miscUtil.scannerProxy().reg(this)
				else if(!this.props.barcodeReader && this.binding) this.binding.unreg()
			}		
			
		}
		componentWillUnmount(){
			this.binding&&this.binding.unreg()
		}
		render(){
			const children = this.state.status?this.props.children:null
			return $("span",{style:{alignSelf:"center"}}, children);
		}
	}	
	class LogoutTriggerElement extends StatefulComponent{
		componentWillUnmount(){
			this.el&&this.binding&&this.binding.unreg()
		}
		componentDidMount(){
			if(!this.el) return
			this.binding = this.el.ownerDocument.defaultView.logoutTrigger&&this.el.ownerDocument.defaultView.logoutTrigger.reg(this.el,this.props.onClick)
		}
		render(){			
			return $("div",{ref:ref=>this.el=ref, style:{display:"none"}})
		}		
	}
	const transforms= {
		tp:{
			StatusElement,TerminalElement,MJobCell,CustomMeasurerConnectionState,			
			ScannerProxyElement,OCRScannerElement, LogoutTriggerElement
		}		
	}
	return ({transforms})
}
