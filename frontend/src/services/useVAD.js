import { ref, onUnmounted } from 'vue';
let vad = null
const loadVadRuntime = () => {
	if (window.vad?.MicVAD) {
		return Promise.resolve(window.vad.MicVAD)
	}
	return new Promise((resolve, reject) => {
		let attempts = 0
		const timer = window.setInterval(
			() => {
				attempts += 1
				if (window.vad?.MicVAD) {
					clearInterval(timer)
					resolve(window.vad.MicVAD)
				}
				else if (attempts > 80) {
					clearInterval(timer)
					reject(new Error('VAD runtime not available'))
				}
			},
			100
		)
	})
}
export function useVAD({ onSpeechEnd, onSpeechStart }) {
	const isSpeaking = ref(false)
	const start = async() => {
		if (vad) {
			vad.start()
			return
		}
		try {
			const MicVAD = await loadVadRuntime()
			vad = await MicVAD.new({
				model: 'v5',
				baseAssetPath: '/javascript/vad/',
				onnxWASMBasePath: '/javascript/onnx/',
				min_speech_duration_ms: 100,
				onSpeechStart: () => {
					isSpeaking.value = true
					onSpeechStart?.()
				},
				onSpeechEnd: (audio) => {
					isSpeaking.value = false
					onSpeechEnd?.(audio)
				},
				onVADMisfire: () => {
					isSpeaking.value = false
				}
			})
			vad.start()
		}
		catch (error) {
			console.error('Error starting VAD:', error)
		}
	}
	const stop = () => {
		if (vad) {
			vad.pause()
		}
	}
	onUnmounted(() => {
		if (vad) {
			vad.destroy()
			vad = null
		}
	})
	return { isSpeaking, start, stop }
}
