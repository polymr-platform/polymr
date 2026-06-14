import { ref } from 'vue';
const sessionsByWorkspace = new Map()
export const useWorkspaceSessions = (workspaceId) => {
	if (!workspaceId) {
		return ref([])
	}
	if (!sessionsByWorkspace.has(workspaceId)) {
		sessionsByWorkspace.set(workspaceId, ref([]))
	}
	return sessionsByWorkspace.get(workspaceId)
}
