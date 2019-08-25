"use strict";

const Notifications = (window,js) => {	
	const isServiceWorker = 'serviceWorker' in window.navigator
	const isPushManager = 'PushManager' in window
	let _registration
	const registerServiceWorker = () =>{
		window.navigator.serviceWorker.register(js)
		.then(registration =>{
			window.console.log('Service worker successfully registered.')
			_registration = registration
			return registration;
		})
		.catch( err => window.console.error('Unable to register service worker.', err))
	}	
	new Promise((resolve, reject) => {
	  if(!isServiceWorker || !isPushManager) return reject("no serviceWorker or PushManager support!")
      const permissionPromise = window.Notification.requestPermission( result => resolve(result))
      if (permissionPromise) permissionPromise.then(resolve)      
    })
    .then(result => {
      if (result === 'granted') return registerServiceWorker()          
      else window.console.log("no permision for notifications")      
    })
	.catch(err => window.console.log(err))
	let msgCount = 0
	const showNotification = (json) =>{		
		const {title, options} = json
		if(msgCount>=10) return window.console.log(`showNotification, ${msgCount} >=10`)
		msgCount+=1
	    new Promise((resolve,reject)=>{
			if(_registration) resolve(_registration)
			const i = window.setInterval(()=>{
				if(_registration) {
					window.clearInterval(i)
					resolve(_registration)
				}
			},10)
		})
		.then(registration=>{
			msgCount-=1
			registration.showNotification(title, options)
		})		
	}
	
	return {showNotification}
}
let _notifications
const initNote = (window, js) =>{
	if(!_notifications) _notifications = new Notifications(window, js)
	return _notifications
}
const showNotification = window => data =>{
	const json = JSON.parse(data)	
	initNote(window, json.js).showNotification(json)
}

const receivers = window => ({
	showNotification: showNotification(window)
})

export default {receivers}