self.addEventListener(
	'push',
	(event) => {
		if (!event.data) {
			return
		}
		let payload = {}
		try {
			payload = event.data.json()
		}
		catch {
			payload = { title: 'Notification', body: event.data.text() }
		}
		const title = payload.title || 'Notification'
		const options = { body: payload.body || '', data: { url: payload.url || '/' } }
		event.waitUntil(self.registration.showNotification(title, options))
	}
)
self.addEventListener(
	'notificationclick',
	(event) => {
		event.notification.close()
		const url = event.notification?.data?.url || '/'
		event.waitUntil(
			self.clients
					.matchAll({ type: 'window', includeUncontrolled: true })
					.then((clientsArr) => {
					for (const client of clientsArr) {
						if (client.url.includes(url) && 'focus' in client) {
							return client.focus()
						}
					}
					if (self.clients.openWindow) {
						return self.clients.openWindow(url)
					}
					return null
				})
		)
	}
)
