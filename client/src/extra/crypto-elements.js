"use strict";
import React from 'react'

export default function CryptoElements({log,feedback,ui,hwcrypto,atob,parentWindow,StatefulComponent}){
	const FlexGroup = ui.transforms.tp.FlexGroup
	const $ = React.createElement
	const sendError = function(msg){
		const digisign = parentWindow().digisign
		digisign&&digisign.sendError(msg)
	}
	const sendErrorStatus = function(errorCode,errorMsg){
		const digisign = parentWindow().digisign
		digisign&&digisign.sendErrorStatus(errorCode,errorMsg)
	}
	const getIdKey = function(){
		const digisign = parentWindow().digisign
		return digisign&&digisign.md5key
	}
	const getPrepText = function(){
		const digisign = parentWindow().digisign
		return digisign&&digisign.preptext
	}
	const sendPositiveSign = function(){
		const digisign = parentWindow().digisign
		digisign&&digisign.sendPositiveSign()
	}
	const sendPositiveAuth = function(){
		const digisign = parentWindow().digisign
		digisign&&digisign.sendPositiveAuth()
	}	
	const DigiModule = function(){
		const callbacksAcc = [];
		let userCertificate = null;
		let calledCert = false
		function reg(callback){
			
		}
		function requestCertificate(callback,un){
			if(calledCert&&!un) return
			calledCert = true
			if(userCertificate) {callback(userCertificate); return}
			hwcrypto.getCertificate({}).then(
				certificate=> {
					userCertificate = certificate					
					callback(certificate)										
				},
				error=>{
					callback(null)
					sendError(error.toString())
				}
			)
		}
		return {reg,requestCertificate}
	}()
	const sendToServer = (branchKey,type,value) =>{
		const app = "digisign"
		feedback.send({
			url:"/connection",
			options:{
				headers:{
					"X-r-app":app,
					"X-r-type":type,
					"X-r-mdkey":getIdKey(),					
					"X-r-branch":branchKey
				},
				body:value
			}
		})
	}
	let sentQuery = false
	const UserQueryStringElement = ({props}) => {		
		const ref = (ref) =>{
			if(sentQuery) return
			sendToServer(props.branchKey,"queryString","needScenario")
			sentQuery = true
		}
		return $("span",{id:"queryString", ref});
	}
	
	
	class UserCertificateElement extends StatefulComponent {		
		onCertificate(certificate){
			if(certificate == null)
				sendToServer(this.props.branchKey,"error","")
			else
				sendToServer(this.props.branchKey,"certificate",certificate.encoded)			
		}
		componentDidMount(){
			DigiModule.requestCertificate(this.onCertificate);
		}		
		render(){
			return $("span",{id:"userCert"});
		}
	}
	let signedDigest = false
	class SignDigestElement extends StatefulComponent{			
		onCertificate(certificate){
			const digest64 = this.props.digest
			const digest = Uint8Array.from(atob(digest64), c => c.charCodeAt(0))			
			hwcrypto.sign(certificate, {type: 'SHA-256', value: digest}, {}).then(signature => {				
				sendToServer(this.props.branchKey,"signature",signature.value)			 
		    }, error =>{
				sendToServer(this.props.branchKey,"error","")
				sendError(error.toString())}
			);
			return true;
		}
		signDigest(digest64){			
			DigiModule.requestCertificate(this.onCertificate,true)
			return true
		}
		componentDidMount(){
			if(!signedDigest) signedDigest = this.signDigest();
		}
		componentDidUpdate(prevProps,_){
			if(this.props.digest != prevProps.digest)
				signedDigest = this.signDigest();
		}		
		render(){ 
			return $("span",{id:"signDigest"});
		}
	}
	let sentPositiveSign = false	
	class ReportDigiStatusElement extends StatefulComponent{				
		getInitialState(){return {width:0}}	
		updateStatus(statusMsg){
			const halves = statusMsg.trim().split(':')
			this.setState({width:(halves[0]*100/halves[1])})
			if(halves.length == 2 && halves[0] == halves[1]){
				if(!sentPositiveSign){
					sendToServer(this.props.branchKey,"success","")
					sendPositiveSign()					
					sentPositiveSign = true;					
				}
			}
			return true;
		}
		componentDidMount(){
			this.updateStatus(this.props.statusMsg)			
		}
		componentDidUpdate(prevProps,_){
			if(this.props.statusMsg!=prevProps.statusMsg){
				this.updateStatus(this.props.statusMsg)
			}			
		}
		render(){
			const style = {
				position:"fixed",
				top:"30%",
				left:"50%",
				width:"50%",
				marginLeft:"-25%",
				zIndex:"669",				
			}
			const progressStyle = {
				display:"block",
				position:"relative",
				width:"100%",
				height:"auto",
				margin:"0.625em 0",
				backgroundColor:"#eeeeee",
				overflow:"hidden",
				boxSizing:"border-box",
				
			}
			const progressIndStyle = {
				width:this.state.width+"%",
				height:"1em",
				"float":"left",
				backgroundColor:"#1ba1e2",				
			}
			const serverMsgStyle = {
				display:this.props.serverMsg?"inline-block":"none",
				margin:"1.3em auto"
			}
			const caption = !this.props.caption?getPrepText():this.props.caption
			const bp = "666"
			if(!this.props.caption && sentPositiveSign) return $('span',{id:"reportDigi"})
			return $(FlexGroup,{id:"reportDigi",style,caption,bp},[
				$("div",{key:"progress",style:progressStyle},
					$("div",{style:progressIndStyle})
				),
				$("div",{key:"msg",style:{textAlign:"center"}},
					$("div",{style:serverMsgStyle},this.props.serverMsg)
				)
			]);
		}
	}
	let sentErrorStatus = false
	let sentAuth = false
	class DigiHandlerElement extends StatefulComponent{
		reportError(errorCode,errorMsg){
			if(errorMsg && !sentErrorStatus){
				sendErrorStatus(errorCode,errorMsg)
				sentErrorStatus = true
			}
		}
		reportAuth(authMsg){
			if(authMsg && !sentAuth){
				sendToServer(this.props.branchKey,"success","")
				sendPositiveAuth()
				sentAuth = true
			}
		}
		componentDidMount(){
			this.reportError(this.props.errorCode,this.props.errorMsg)
			this.reportAuth(this.props.authMsg)
		}
		componentDidUpdate(prevProps,_){
			this.reportError(this.props.errorCode,this.props.errorMsg)
			this.reportAuth(this.props.authMsg)
		}
		render(){
			return $("span",{id:"handler"})
		}
	}
	const transforms= {
		tp:{
			UserQueryStringElement,UserCertificateElement,SignDigestElement,ReportDigiStatusElement,DigiHandlerElement
		}
	};
	const receivers = {};
	return {transforms,receivers};
}