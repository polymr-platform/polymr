import { ref } from 'vue';
import { openWorkspaceSocket } from '../api';
const sockets = new Map()
const buildKey = (tenantId, workspaceId) => `${tenantId || ''}:${workspaceId || ''}`
const createState = (tenantId, workspaceId) => {
	const socket = ref(null)
	const connected = ref(false)
	const initialized = ref(false)
	const connectionId = ref('')
	const handlers = new Set()
	let reconnectTimer = null
	let reconnectAttempts = 0
	let connectPromise = null
	const dispatchMessage = (payload) => {
		if (payload?.type === 'ws.connected') {
			connectionId.value = payload?.payload?.connection_id || ''
		}
		handlers.forEach((handler) => {
				try {
					handler?.(payload)
				}
				catch (error) {
					console.error('Workspace socket handler failed', error)
				}
			})
	}
	const scheduleReconnect = () => {
		if (reconnectTimer || handlers.size === 0) {
			return
		}
		reconnectAttempts += 1
		const delay = Math.min(15000, 250 * Math.pow(1.6, reconnectAttempts))
		reconnectTimer = setTimeout(
			() => {
				reconnectTimer = null
				connect()
			},
			delay
		)
	}
	const connect = async() => {
		if (!tenantId || !workspaceId) {
			return
		}
		if (connectPromise) {
			return connectPromise
		}
		if (socket.value) {
			if (socket.value.readyState === WebSocket.OPEN || socket.value.readyState === WebSocket.CONNECTING) {
				return
			}
		}
		try {
			connectPromise = openWorkspaceSocket(tenantId, workspaceId, dispatchMessage)
			const ws = await connectPromise
			socket.value = ws
			ws.onopen = () => {
				if (socket.value !== ws) {
					return
				}
				connected.value = true
				initialized.value = true
				reconnectAttempts = 0
				if (reconnectTimer) {
					clearTimeout(reconnectTimer)
					reconnectTimer = null
				}
			}
			ws.onclose = () => {
				if (socket.value !== ws) {
					return
				}
				connectionId.value = ''
				connected.value = false
				initialized.value = true
				scheduleReconnect()
			}
			ws.onerror = () => {
				if (socket.value !== ws) {
					return
				}
				connected.value = false
				initialized.value = true
				scheduleReconnect()
			}
			if (ws.readyState === WebSocket.OPEN) {
				ws.onopen?.()
			}
		}
		catch (error) {
			console.error('Workspace socket connect failed', error)
			connectionId.value = ''
			connected.value = false
			initialized.value = true
			scheduleReconnect()
		}
		finally {
			connectPromise = null
		}
	}
	const send = (payload) => {
		const ws = socket.value
		if (!ws || ws.readyState !== WebSocket.OPEN) {
			connected.value = false
			return false
		}
		connected.value = true
		ws.send(JSON.stringify(payload))
		return true
	}
	const registerHandler = (handler) => {
		handlers.add(handler)
		connect()
		return () => {
			handlers.delete(handler)
		}
	}
	const close = () => {
		if (socket.value) {
			socket.value.close()
		}
		socket.value = null
		connectionId.value = ''
		connected.value = false
	}
	return {
		socket,
		connected,
		initialized,
		connectionId,
		send,
		registerHandler,
		connect,
		close
	}
}
export const useWorkspaceSocket = (tenantId, workspaceId) => {
	const key = buildKey(tenantId, workspaceId)
	if (!sockets.has(key)) {
		sockets.set(key, createState(tenantId, workspaceId))
	}
	return sockets.get(key)
}
