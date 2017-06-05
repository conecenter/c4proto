"use strict";
import React from 'react'

export default function CryptoElements({log,ui,hwcrypto,atob,parentWindow}){
	const FlexGroup = ui.transforms.tp.FlexGroup
	const sendError = function(msg){
		const digisign = parentWindow().digisign
		digisign&&digisign.sendError(msg)
	}
	const getIdKey = function(){
		const digisign = parentWindow().digisign
		return digisign&&digisign.md5key
	}
	const getPrepText = function(){
		const digisign = parentWindow().digisign
		return digisign&&digisign.preptext
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
					log(certificate)
					callback(certificate)										
				},
				error=>sendError(error.toString())
			)
		}
		return {reg,requestCertificate}
	}()	
	const UserCertificateElement = React.createClass({
		onCertificate:function(certificate){			
			if(this.props.onReadySendBlob)						
				this.props.onReadySendBlob(getIdKey(),certificate.encoded);
		},
		componentDidMount:function(){
			DigiModule.requestCertificate(this.onCertificate);
		},
		componentWillUnmount:function(){
			
		},
		render:function(){
			return React.createElement("span",{id:"userCert"});
		}
	});
	let signedDigest = false
	const SignDigestElement = React.createClass({
		onCertificate:function(certificate){
			const digest64 = this.props.digest
			const digest = Uint8Array.from(atob(digest64), c => c.charCodeAt(0))			
			hwcrypto.sign(certificate, {type: 'SHA-256', value: digest}, {}).then(signature => {			  
			  log(signature);
			  if(this.props.onReadySendBlob)
				  this.props.onReadySendBlob(getIdKey(),signature.value)
		    }, error =>sendError(error.toString()));
			return true;
		},
		signDigest:function(digest64){			
			DigiModule.requestCertificate(this.onCertificate,true)
			return true
		},
		componentDidMount:function(){
			if(!signedDigest) signedDigest = this.signDigest();
		},
		componentDidUpdate:function(prevProps,_){
			if(this.props.digest != prevProps.digest)
				signedDigest = this.signDigest();
		},
		componentWillUnmount:function(){},
		render:function(){ 
			return React.createElement("span",{id:"signDigest"});
		}
	})
	let sentPositiveAuth = false
	const ReportDigiStatusElement = React.createClass({
		getInitialState:function(){
			return {width:0}
		},
		call:function(statusMsg){
			const halves = statusMsg.trim().split(':')
			this.setState({width:(halves[0]*100/halves[1])})
			if(halves.length == 2 && halves[0] == halves[1]){
				if(!sentPositiveAuth){
					sendPositiveAuth()					
					sentPositiveAuth = true;
				}
			}
			return true;
		},
		componentDidMount:function(){
			this.call(this.props.statusMsg)
		},
		componentDidUpdate:function(prevProps,_){
			if(this.props.statusMsg!=prevProps.statusMsg){
				this.call(this.props.statusMsg)
			}
		},
		render:function(){
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
			const caption = !this.props.caption?getPrepText():this.props.caption
			const bp = "666"
			return React.createElement(FlexGroup,{id:"reportDigi",style,caption,bp},
				React.createElement("div",{style:progressStyle},
					React.createElement("div",{style:progressIndStyle})
				)
			);
		}
	})
	
	const transforms= {
		tp:{
			UserCertificateElement,SignDigestElement,ReportDigiStatusElement
		}
	};
	const receivers = {};
	return {transforms,receivers};
}