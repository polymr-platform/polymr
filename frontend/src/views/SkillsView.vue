<script setup>
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
const router = useRouter()
onMounted(() => {
	router.replace({ name: 'tenant' })
})
</script>
<template>
	<section class="detail-view">
		<article class="panel">
			<h2>Redirecting</h2>
			<p>Skill management is available on the tenant page.</p>
		</article>
	</section>
</template>
