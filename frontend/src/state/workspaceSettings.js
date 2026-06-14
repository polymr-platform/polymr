const getScopedKey = (workspaceId, key) => {
	if (!workspaceId) {
		return key
	}
	return `${key}.${workspaceId}`
}
export const getWorkspaceSetting = (workspaceId, key) => localStorage.getItem(getScopedKey(workspaceId, key))
export const setWorkspaceSetting = (workspaceId, key, value) => {
	localStorage.setItem(getScopedKey(workspaceId, key), value)
}
