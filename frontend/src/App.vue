<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watchEffect } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { clearSession, getProfile, loadSession, logout } from './api';
const route = useRoute()
const router = useRouter()
const theme = ref('dark')
const themeKey = 'polymr.theme'
const session = ref(loadSession())
const profile = ref(null)
const userMenuOpen = ref(false)
let menuCleanup = null
const isAuthRoute = computed(() => route.path === '/login')
const isAuthenticated = computed(() => Boolean(session.value?.userId))
watchEffect(() => {
	document.documentElement.setAttribute('data-theme', theme.value)
})
watchEffect(() => {
	route.fullPath
	session.value = loadSession()
})
const setTheme = (next) => {
	theme.value = next
	localStorage.setItem(themeKey, next)
}
const handleLogout = async() => {
	try {
		await logout()
	}
	finally {
		clearSession()
		router.push('/login')
	}
}
const loadProfile = async() => {
	const userId = session.value?.userId
	if (!userId) {
		profile.value = null
		return
	}
	try {
		profile.value = await getProfile(userId)
	}
	catch {
		profile.value = null
	}
}
watchEffect(() => {
	const userId = session.value?.userId
	if (userId) {
		loadProfile()
	}
	else {
		profile.value = null
	}
})
const displayName = computed(() => {
	const preferred = profile.value?.nickname
		|| session.value?.email
	return preferred || 'User'
})
const avatarUrl = computed(() => profile.value?.avatar_url || '')
const avatarInitial = computed(() => {
	const name = displayName.value
	return name ? name.trim().charAt(0).toUpperCase() : '?'
})
const avatarPalette = [
	'#1E3A5F',
	'#1B4B4F',
	'#2A4B8D',
	'#355C7D',
	'#2D5C6E',
	'#2F4C65',
	'#1F4E4A',
	'#2C3E63',
	'#254E70',
	'#294458',
	'#204A6B',
	'#1D4D57',
]
const avatarColor = computed(() => {
	const seed = (session.value?.userId || '').toString()
	if (!seed) {
		return avatarPalette[0]
	}
	let hash = 0
	for (let i = 0; i < seed.length; i += 1) {
		hash = (hash << 5) - hash + seed.charCodeAt(i)
		hash |= 0
	}
	return avatarPalette[Math.abs(hash) % avatarPalette.length]
})
const toggleUserMenu = () => {
	userMenuOpen.value = !userMenuOpen.value
}
const closeUserMenu = () => {
	userMenuOpen.value = false
}
const goToProfile = () => {
	closeUserMenu()
	router.push('/profile')
}
const goToTenant = () => {
	router.push('/tenant')
}
const handleMenuClick = (event) => {
	const target = event.target
	if (!(target instanceof Element)) {
		return
	}
	if (!target.closest('.user-menu')) {
		closeUserMenu()
	}
}
onMounted(() => {
	const storedTheme = localStorage.getItem(themeKey)
	if (storedTheme === 'light' || storedTheme === 'dark') {
		theme.value = storedTheme
	}
	loadProfile()
	document.addEventListener('click', handleMenuClick)
	menuCleanup = () => document.removeEventListener('click', handleMenuClick)
})
onBeforeUnmount(() => {
	menuCleanup?.()
})
</script>
<template>
	<div class="app-shell">
		<header class="app-header" :class="{ minimal: isAuthRoute }">
			<div class="brand">
				<button
					class="brand-mark"
					type="button"
					@click="goToTenant"
					aria-label="Go to tenants"><img src="/src/assets/polymr-mark.svg" alt="Polymr"/></button>
				<div class="brand-title">
					<button
						class="brand-name brand-home"
						type="button"
						@click="goToTenant">Polymr</button>
				</div>
			</div>
			<div class="header-actions">
				<div v-if="!isAuthRoute && isAuthenticated" class="user-menu">
					<button
						class="control size-s ghost user-toggle"
						type="button"
						@click="toggleUserMenu">
						<span class="user-avatar" :style="{ backgroundColor: avatarUrl ? '' : avatarColor }">
							<img
								v-if="avatarUrl"
								:src="avatarUrl"
								alt=""/>
							<span v-else class="user-initial">{{ avatarInitial }}</span>
						</span>
						<span class="user-name">{{ displayName }}</span>
					</button>
					<div v-if="userMenuOpen" class="user-dropdown">
						<button
							class="control size-s ghost"
							type="button"
							@click="goToProfile">Profile</button>
						<button
							class="control size-s ghost"
							type="button"
							:disabled="theme === 'dark'"
							@click="setTheme('dark')">Dark theme</button>
						<button
							class="control size-s ghost"
							type="button"
							:disabled="theme === 'light'"
							@click="setTheme('light')">Light theme</button>
						<button
							class="control size-s ghost"
							type="button"
							@click="handleLogout">Log out</button>
					</div>
				</div>
			</div>
		</header>
		<main class="app-main">
			<RouterView/>
		</main>
	</div>
</template>
