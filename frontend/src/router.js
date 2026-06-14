import { createRouter, createWebHistory } from 'vue-router';
import {
	clearSession,
	ensureAccessToken,
	getTenants,
	updateSessionTenants,
	loadActiveTenant,
	saveActiveTenant
} from './api';
import LoginView from './views/LoginView.vue';
import TenantView from './views/TenantView.vue';
import AssistantsView from './views/AssistantsView.vue';
import RulesView from './views/RulesView.vue';
import PersonasView from './views/PersonasView.vue';
import SkillsView from './views/SkillsView.vue';
import ProfileView from './views/ProfileView.vue';
import WorkspaceLayout from './views/WorkspaceLayout.vue';
import WorkspaceDetailView from './views/WorkspaceDetailView.vue';
import SessionView from './views/SessionView.vue';
import HistoryView from './views/HistoryView.vue';
import WorkflowEditorView from './views/WorkflowEditorView.vue';
import WorkspaceAppView from './views/WorkspaceAppView.vue';
import RecordingsView from './views/RecordingsView.vue';
import LogsView from './views/LogsView.vue';
import PageEditorView from './views/PageEditorView.vue';
import PageRuntimeView from './views/PageRuntimeView.vue';
import ScriptEditorView from './views/ScriptEditorView.vue';
const routes = [
	{
		path: '/',
		name: 'root',
		beforeEnter: async() => {
			try {
				const tenants = await getTenants()
				updateSessionTenants(tenants)
				const active = loadActiveTenant()
				const hasActive = active && tenants.some((tenant) => tenant.id === active)
				if (!hasActive && tenants.length) {
					saveActiveTenant(tenants[0].id)
				}
				return { name: 'tenant', state: { skipTenantLoad: true } }
			}
			catch {
				clearSession()
				return { path: '/login' }
			}
		}
	},
	{ path: '/login', name: 'login', component: LoginView },
	{ path: '/tenant', name: 'tenant', component: TenantView },
	{ path: '/tenant/assistants', name: 'assistants', component: AssistantsView },
	{ path: '/tenant/rules', name: 'rules', component: RulesView },
	{ path: '/tenant/personas', name: 'personas', component: PersonasView },
	{ path: '/tenant/skills', name: 'skills', component: SkillsView },
	{
		path: '/workspace/:workspaceId',
		component: WorkspaceLayout,
		children: [
			{ path: '', name: 'workspace-detail', component: WorkspaceDetailView },
			{ path: 'history', name: 'workspace-history', component: HistoryView },
			{ path: 'recordings', name: 'workspace-recordings', component: RecordingsView },
			{ path: 'logs', name: 'workspace-logs', component: LogsView },
			{ path: 'apps/:appId', name: 'workspace-app', component: WorkspaceAppView },
			{ path: 'pages/:pageId/edit', name: 'workspace-page-editor', component: PageEditorView },
			{ path: 'scripts/:scriptId/edit', name: 'workspace-script-editor', component: ScriptEditorView },
			{ path: 'pages/:slug/:routeSuffix(.*)*', name: 'workspace-page-runtime', component: PageRuntimeView },
			{ path: 'sessions/:sessionId', name: 'workspace-session', component: SessionView },
			{ path: 'workflows/:workflowId', name: 'workspace-workflow', component: WorkflowEditorView },
			{ path: 'sessions', redirect: (to) => ({ name: 'workspace-history', params: to.params }) },
		]
	},
	{ path: '/profile', name: 'profile', component: ProfileView },
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async(to) => {
		if (to.path === '/login') {
			return true
		}
		const token = await ensureAccessToken()
		if (!token) {
			clearSession()
			return { path: '/login' }
		}
		return true
	})
export default router
