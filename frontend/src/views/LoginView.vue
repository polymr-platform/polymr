<script setup>
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { login, register, saveSession } from '../api';
const router = useRouter()
const mode = ref('login')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const isSubmitting = ref(false)
const isRegister = computed(() => mode.value === 'register')
const toggleMode = () => {
	mode.value = isRegister.value ? 'login' : 'register'
	error.value = ''
}
const submitLabel = computed(() => (isRegister.value ? 'Create account' : 'Sign in'))
const submit = async() => {
	error.value = ''
	if (!email.value || !password.value) {
		error.value = 'Email and password are required.'
		return
	}
	if (isRegister.value && password.value !== confirmPassword.value) {
		error.value = 'Passwords do not match.'
		return
	}
	isSubmitting.value = true
	try {
		const payload = { email: email.value, password: password.value }
		const session = isRegister.value ? await register(payload) : await login(payload)
		saveSession(session)
		router.push('/tenant')
	}
	catch (err) {
		error.value = err.message || 'Login failed.'
	}
	finally {
		isSubmitting.value = false
	}
}
</script>
<template>
	<section class="auth-grid">
		<div class="auth-panel">
			<div class="panel-header">
				<p class="eyebrow">Access</p>
				<h1>{{ isRegister ? 'Create your Polymr account' : 'Welcome back' }}</h1>
				<p class="subtle">
					{{
            isRegister
              ? 'Set up your account with personal tenant and get started.'
              : 'Sign in to manage workspaces, configure assistants, and tune rules.'
          }}
				</p>
			</div>
			<form class="auth-form" @submit.prevent="submit">
				<label class="field">
					<span>Email</span>
					<input
						v-model="email"
						type="email"
						autocomplete="email"
						placeholder="you@domain.com"/>
				</label>
				<label class="field">
					<span>Password</span>
					<input
						v-model="password"
						type="password"
						autocomplete="current-password"
						placeholder="••••••••"/>
				</label>
				<label v-if="isRegister" class="field">
					<span>Confirm password</span>
					<input
						v-model="confirmPassword"
						type="password"
						autocomplete="new-password"
						placeholder="••••••••"/>
				</label>
				<p v-if="error" class="form-error">{{ error }}</p>
				<button
					class="control size-m primary"
					type="submit"
					:disabled="isSubmitting">{{ isSubmitting ? 'Working...' : submitLabel }}</button>
			</form>
			<div class="auth-footer">
				<span>{{ isRegister ? 'Already have an account?' : 'No account yet?' }}</span>
				<button
					class="link"
					type="button"
					@click="toggleMode">{{ isRegister ? 'Sign in instead' : 'Create account' }}</button>
			</div>
		</div>
	</section>
</template>
