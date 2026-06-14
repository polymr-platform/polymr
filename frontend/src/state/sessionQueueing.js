import { ref } from 'vue';
const storesByWorkspace = new Map()
const buildKey = (workspaceId) => `${workspaceId || ''}`
export const useSessionQueueing = (workspaceId) => {
	const key = buildKey(workspaceId)
	if (!storesByWorkspace.has(key)) {
		storesByWorkspace.set(key, { queues: ref({}), pending: ref({}), timers: ref({}) })
	}
	return storesByWorkspace.get(key)
}
export const flushQueuedMessage = (queuesRef, pendingRef, sessionId, send) => {
	const list = queuesRef.value[sessionId]
	if (!list || list.length === 0) {
		return null
	}
	if (pendingRef.value[sessionId]) {
		return null
	}
	const [next, ...rest] = list
	const sent = send({ type: 'chat.send', session_id: sessionId, payload: next })
	if (!sent) {
		return null
	}
	pendingRef.value = { ...pendingRef.value, [sessionId]: true }
	queuesRef.value = { ...queuesRef.value, [sessionId]: rest }
	return next
}
export const clearPending = (pendingRef, sessionId) => {
	if (!pendingRef.value[sessionId]) {
		return
	}
	const next = { ...pendingRef.value }
	delete next[sessionId]
	pendingRef.value = next
}
const clearTimer = (timersRef, sessionId) => {
	const timer = timersRef.value[sessionId]
	if (timer) {
		clearTimeout(timer)
	}
	const nextTimers = { ...timersRef.value }
	delete nextTimers[sessionId]
	timersRef.value = nextTimers
}
const scheduleDequeue = ({ queuesRef, pendingRef, timersRef, sessionId, send, jitter }) => {
	if (timersRef.value[sessionId]) {
		return
	}
	const min = Math.max(0, jitter?.min ?? 0)
	const max = Math.max(min, jitter?.max ?? min)
	const delay = Math.floor(Math.random() * (max - min + 1)) + min
	const timer = setTimeout(
		() => {
			clearTimer(timersRef, sessionId)
			flushQueuedMessage(queuesRef, pendingRef, sessionId, send)
		},
		delay
	)
	timersRef.value = { ...timersRef.value, [sessionId]: timer }
}
export const handleSessionStatusEvent = ({
  event,
  queuesRef,
  pendingRef,
  timersRef,
  smartQueueing,
  send,
  jitter,
}) => {
	if (!event || event.type !== 'session.status' || !event.payload?.id) {
		return null
	}
	const sessionId = event.payload.id
	const locked = !!event.payload.locked
	if (locked) {
		clearPending(pendingRef, sessionId)
		if (timersRef) {
			clearTimer(timersRef, sessionId)
		}
		return null
	}
	if (!smartQueueing) {
		return null
	}
	if (timersRef) {
		scheduleDequeue({
			queuesRef,
			pendingRef,
			timersRef,
			sessionId,
			send,
			jitter
		})
		return null
	}
	return flushQueuedMessage(queuesRef, pendingRef, sessionId, send)
}
export const handleSessionLeave = ({
  sessionId,
  queuesRef,
  smartQueueing,
}) => {
	if (!sessionId || smartQueueing) {
		return
	}
	const nextQueue = { ...queuesRef.value }
	delete nextQueue[sessionId]
	queuesRef.value = nextQueue
}
