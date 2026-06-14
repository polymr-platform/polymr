const DEFAULT_VOICE = 'en_US-libritts_r-medium'
const TTS_PRE_ROLL_MS = 750
let sessionPromise = null
let modulePromise = null
let currentAudio = null
let currentSource = null
let audioContext = null
let playbackInFlight = false
const queue = []
const SILENCE_ITEM = { type: 'silence' }
let enabled = true
let activeVoiceId = null
const playStartListeners = new Set()
const playEndListeners = new Set()
const getVoiceId = () => localStorage.getItem('polymr.tts.voice') || DEFAULT_VOICE
const loadPiperModule = async() => {
	if (!modulePromise) {
		modulePromise = new Promise((resolve, reject) => {
			if (window.PiperTTS?.TtsSession) {
				resolve(window.PiperTTS)
				return
			}
			let attempts = 0
			const timer = window.setInterval(
				() => {
					attempts += 1
					if (window.PiperTTS?.TtsSession) {
						clearInterval(timer)
						resolve(window.PiperTTS)
					}
					else if (attempts > 80) {
						clearInterval(timer)
						reject(new Error('Piper runtime not available'))
					}
				},
				100
			)
		})
	}
	return modulePromise
}
const getSession = async() => {
	const voiceId = getVoiceId()
	if (activeVoiceId && activeVoiceId !== voiceId) {
		resetSession()
	}
	if (!sessionPromise) {
		console.debug('[tts] loading piper module')
		sessionPromise = loadPiperModule()
			.then((module) => {
				if (voiceId.startsWith('custom:')) {
					const slug = voiceId.replace('custom:', '')
					if (module.PATH_MAP) {
						module.PATH_MAP[voiceId] = `custom/${slug}`
					}
				}
				const wasmBase = module.WASM_BASE
					|| 'https://cdn.jsdelivr.net/npm/@diffusionstudio/piper-wasm@1.0.0/build/piper_phonemize'
				const wasmPaths = { onnxWasm: '/javascript/onnx/', piperWasm: `${wasmBase}.wasm`, piperData: `${wasmBase}.data` }
				console.debug('[tts] creating TTS session', voiceId)
				activeVoiceId = voiceId
				return module.TtsSession.create({ voiceId, wasmPaths })
			})
	}
	return sessionPromise
}
const resetSession = () => {
	sessionPromise = null
	activeVoiceId = null
	stopCurrent()
}
const playNext = async() => {
	if (!enabled || currentAudio || playbackInFlight || queue.length === 0) {
		return
	}
	playbackInFlight = true
	const item = queue.shift()
	if (!item) {
		return
	}
	try {
		if (!audioContext) {
			audioContext = new (window.AudioContext || window.webkitAudioContext)()
		}
		if (audioContext.state === 'suspended') {
			await audioContext.resume()
		}
		let buffer = null
		if (item.type === 'silence') {
			const padSamples = Math.round((audioContext.sampleRate * TTS_PRE_ROLL_MS) / 1000)
			buffer = audioContext.createBuffer(1, padSamples, audioContext.sampleRate)
		}
		else {
			const session = await getSession()
			console.debug('[tts] predicting', { length: item.text.length })
			const blob = await session.predict(item.text)
			console.debug('[tts] prediction complete', { size: blob?.size, type: blob?.type })
			const arrayBuffer = await blob.arrayBuffer()
			buffer = await audioContext.decodeAudioData(arrayBuffer)
		}
		const source = audioContext.createBufferSource()
		source.buffer = buffer
		source.connect(audioContext.destination)
		currentSource = source
		currentAudio = source
		playStartListeners.forEach((handler) => handler())
		source.onended = () => {
			console.debug('[tts] playback ended')
			currentSource = null
			currentAudio = null
			playEndListeners.forEach((handler) => handler())
			playNext()
		}
		source.start(0)
	}
	catch (error) {
		console.debug('[tts] playback failed', error)
		currentSource = null
		currentAudio = null
		playNext()
	}
	finally {
		playbackInFlight = false
	}
}
const enqueue = (text) => {
	if (!enabled) {
		return
	}
	if (!text || !text.trim()) {
		return
	}
	console.debug('[tts] enqueue', text)
	const trimmed = text.trim()
	// Add a short silence before the first clip so the audio pipeline warms up.
	// Without this, the first few syllables can be clipped by browser playback.
	if (!currentAudio) {
		queue.push(SILENCE_ITEM)
	}
	queue.push({ type: 'text', text: trimmed })
	playNext()
}
const stopCurrent = () => {
	playbackInFlight = false
	if (currentSource) {
		console.debug('[tts] stop current')
		try {
			currentSource.stop(0)
		}
		catch {
			// ignore
		}
		currentSource.disconnect()
		currentSource = null
		currentAudio = null
		playEndListeners.forEach((handler) => handler())
	}
}
const setEnabled = (value) => {
	enabled = value
	console.debug('[tts] enabled', enabled)
	if (!enabled) {
		stopCurrent()
	}
}
const onPlayStart = (handler) => {
	playStartListeners.add(handler)
	return () => playStartListeners.delete(handler)
}
const onPlayEnd = (handler) => {
	playEndListeners.add(handler)
	return () => playEndListeners.delete(handler)
}
export const usePiperTts = () => ({
	enqueue,
	stopCurrent,
	setEnabled,
	onPlayStart,
	onPlayEnd,
	resetSession
})
