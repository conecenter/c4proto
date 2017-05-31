"use strict";
import React from 'react'

export default function CryptoElements({log,hwcrypto,atob,parentWindow}){
	
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
				error=>parentWindow&&parentWindow().digisign.sendError(error.toString())
			)
		}
		return {reg,requestCertificate}
	}()	
	const UserCertificateElement = React.createClass({
		onCertificate:function(certificate){			
			if(this.props.onReadySendBlob)						
				this.props.onReadySendBlob("certificate",certificate.encoded);
		},
		componentDidMount:function(){
			DigiModule.requestCertificate(this.onCertificate);
		},
		componentWillUnmount:function(){
			
		},
		render:function(){
			return null;
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
				  this.props.onReadySendBlob("signature",signature.value)
		    }, error => parentWindow&&parentWindow().digisign.sendError(error.toString()));
			return true;
		},
		signDigest:function(digest64){			
			DigiModule.requestCertificate(this.onCertificate,true)
			return true
		},
		componentDidMount:function(){
			if(!signedDigest) signedDigest = this.signDigest();
		},
		componentWillUnmount:function(){},
		render:function(){ return null}
	})
	
	const transforms= {
		tp:{
			UserCertificateElement,SignDigestElement
		}
	};
	const receivers = {};
	return {transforms,receivers};
}