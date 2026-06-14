let trackingEnabled = false
let refreshTimer = null
let lastLocation = null
const refreshLocation = () => {
	if (!trackingEnabled || !navigator.geolocation) {
		return
	}
	navigator.geolocation
		.getCurrentPosition(
			(position) => {
				if (!position?.coords) {
					return
				}
				lastLocation = { lat: position.coords.latitude, lng: position.coords.longitude }
			},
			() => {},
			{ enableHighAccuracy: false, timeout: 3000, maximumAge: 60000 }
		)
}
export const setLocationTrackingEnabled = (enabled) => {
	trackingEnabled = !!enabled
	if (!trackingEnabled) {
		lastLocation = null
		if (refreshTimer) {
			clearInterval(refreshTimer)
			refreshTimer = null
		}
		return
	}
	refreshLocation()
	if (!refreshTimer) {
		refreshTimer = setInterval(refreshLocation, 5 * 60 * 1000)
	}
}
export const getLastLocation = () => (trackingEnabled ? lastLocation : null)
export const isLocationTrackingEnabled = () => trackingEnabled
