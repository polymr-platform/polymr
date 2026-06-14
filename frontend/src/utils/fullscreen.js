export const isElementFullscreen = (element) => {
	if (!element || typeof document === 'undefined') {
		return false
	}
	return document.fullscreenElement === element
}
export const toggleElementFullscreen = async(element) => {
	if (!element || typeof document === 'undefined') {
		return false
	}
	if (document.fullscreenElement === element) {
		await document.exitFullscreen()
		return false
	}
	await element.requestFullscreen()
	return true
}
