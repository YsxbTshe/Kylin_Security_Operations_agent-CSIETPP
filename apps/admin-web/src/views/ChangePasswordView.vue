<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { extractErrorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { Alert, Button, FormField, Input } from '../ui'

const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)
const error = ref('')
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })

async function handleSubmit() {
  if (form.newPassword !== form.confirmPassword) {
    error.value = '两次输入的新口令不一致'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await authStore.updatePassword(form.currentPassword, form.newPassword)
    await router.replace('/agent-runs')
  } catch (err) {
    error.value = extractErrorMessage(err, '修改口令失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="change-password-page">
    <section class="card">
      <h1>修改初始密码</h1>
      <p>首次登录或管理员重置后，必须更换口令才能进入平台。</p>
      <Alert v-if="error" tone="danger" :title="error" />
      <form @submit.prevent="handleSubmit">
        <FormField label="当前口令">
          <Input v-model="form.currentPassword" type="password" autocomplete="current-password" />
        </FormField>
        <FormField label="新口令">
          <Input v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="至少 8 位，含字母与数字" />
        </FormField>
        <FormField label="确认新口令">
          <Input v-model="form.confirmPassword" type="password" autocomplete="new-password" />
        </FormField>
        <Button type="submit" variant="primary" block :loading="loading">保存并进入</Button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.change-password-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  background: var(--bg);
}
.card {
  width: min(420px, 100%);
  padding: 32px 28px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--surface);
}
h1 { font-size: 24px; margin-bottom: 8px; }
p { color: var(--text-3); font-size: 13px; margin-bottom: 22px; }
form { display: flex; flex-direction: column; gap: 16px; }
</style>
