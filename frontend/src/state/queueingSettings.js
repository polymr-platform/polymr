const SMART_QUEUEING_KEY = 'polymr.queueing.smart'
const QUEUE_JITTER_MIN_KEY = 'polymr.queueing.jitter.min'
const QUEUE_JITTER_MAX_KEY = 'polymr.queueing.jitter.max'
export const loadSmartQueueing = () => {
	const raw = localStorage.getItem(SMART_QUEUEING_KEY)
	if (raw === null) {
		return true
	}
	return raw === 'true'
}
export const saveSmartQueueing = (value) => {
	localStorage.setItem(SMART_QUEUEING_KEY, value ? 'true' : 'false')
}
export const loadQueueingJitter = () => {
	const minRaw = localStorage.getItem(QUEUE_JITTER_MIN_KEY)
	const maxRaw = localStorage.getItem(QUEUE_JITTER_MAX_KEY)
	const min = minRaw ? Number(minRaw) : 300
	const max = maxRaw ? Number(maxRaw) : 800
	return { min: Number.isFinite(min) ? min : 300, max: Number.isFinite(max) ? max : 800 }
}
