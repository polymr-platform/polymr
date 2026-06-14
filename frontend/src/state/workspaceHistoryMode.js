import { ref } from 'vue';
const historyByWorkspace = new Map()
export const useWorkspaceHistoryMode = (workspaceId) => {
	if (!workspaceId) {
		return ref(false)
	}
	if (!historyByWorkspace.has(workspaceId)) {
		historyByWorkspace.set(workspaceId, ref(false))
	}
	return historyByWorkspace.get(workspaceId)
}
