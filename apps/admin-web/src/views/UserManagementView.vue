<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchOrganizations } from '../api/organizations'
import { fetchRoles } from '../api/roles'
import {
  createUser,
  fetchUsers,
  updateUser,
  uploadUserAvatar,
  type CreateUserPayload,
  type UpdateUserPayload,
  type UserView,
} from '../api/users'
import { useAuthStore } from '../stores/auth'
import {
  Avatar,
  Button,
  DataTable,
  Dialog,
  FormField,
  Input,
  MultiSelect,
  PageHeader,
  SectionCard,
  Select,
  Switch,
  Tag,
  toast,
  type DataTableColumn,
  type SelectOption,
} from '../ui'

const authStore = useAuthStore()
const loading = ref(false)
const metaLoading = ref(false)
const submitting = ref(false)
const avatarUploading = ref(false)
const dialogOpen = ref(false)
const editingUserId = ref('')
const fileInput = ref<HTMLInputElement>()
const users = ref<UserView[]>([])
const roles = ref<{ code: string; name: string; description: string; permissions: string[] }[]>([])
const organizations = ref<{ id: string; code: string; name: string; hostCount: number; memberCount: number }[]>([])

const form = reactive({
  username: '',
  displayName: '',
  email: '',
  avatarUrl: '',
  password: '',
  enabled: true,
  roleCodes: [] as string[],
  organizationIds: [] as string[],
  defaultOrganizationId: '',
})

const canWrite = computed(() => authStore.hasPermission('user:write'))
const dialogTitle = computed(() => (editingUserId.value ? '编辑用户' : '新建用户'))

const roleOptions = computed<SelectOption[]>(() =>
  roles.value.map((role) => ({ label: role.name, value: role.code })),
)
const organizationOptions = computed<SelectOption[]>(() =>
  organizations.value.map((org) => ({ label: org.name, value: org.id })),
)
const defaultOrganizationOptions = computed<SelectOption[]>(() =>
  organizationOptions.value.filter((option) => form.organizationIds.includes(option.value)),
)

const columns: DataTableColumn[] = [
  { key: 'user', label: '用户', minWidth: '220px' },
  { key: 'email', label: '邮箱', minWidth: '200px' },
  { key: 'roles', label: '角色', minWidth: '200px' },
  { key: 'organizations', label: '组织', minWidth: '200px' },
  { key: 'enabled', label: '状态', width: '110px' },
  { key: 'actions', label: '操作', width: '100px' },
]

const userFormInvalid = computed(() => {
  if (!form.displayName.trim() || form.roleCodes.length === 0 || form.organizationIds.length === 0) {
    return true
  }
  if (!form.defaultOrganizationId || !form.organizationIds.includes(form.defaultOrganizationId)) {
    return true
  }
  if (!editingUserId.value && (!form.username.trim() || !form.password.trim())) {
    return true
  }
  return false
})

function resetForm() {
  editingUserId.value = ''
  form.username = ''
  form.displayName = ''
  form.email = ''
  form.avatarUrl = ''
  form.password = ''
  form.enabled = true
  form.roleCodes = []
  form.organizationIds = []
  form.defaultOrganizationId = ''
}

function openCreateDialog() {
  resetForm()
  dialogOpen.value = true
}

function openEditDialog(user: UserView) {
  editingUserId.value = user.id
  form.username = user.username
  form.displayName = user.displayName
  form.email = user.email || ''
  form.avatarUrl = user.avatarUrl || ''
  form.password = ''
  form.enabled = user.enabled
  form.roleCodes = user.roles.map((role) => role.code)
  form.organizationIds = user.organizations.map((organization) => organization.id)
  form.defaultOrganizationId = user.defaultOrganizationId
  dialogOpen.value = true
}

function syncDefaultOrganization() {
  if (form.organizationIds.length === 0) {
    form.defaultOrganizationId = ''
    return
  }
  if (!form.organizationIds.includes(form.defaultOrganizationId)) {
    form.defaultOrganizationId = form.organizationIds[0]
  }
}

async function loadUsers() {
  loading.value = true
  try {
    users.value = await fetchUsers()
  } finally {
    loading.value = false
  }
}

async function loadMeta() {
  metaLoading.value = true
  try {
    const [roleList, organizationList] = await Promise.all([fetchRoles(), fetchOrganizations()])
    roles.value = roleList
    organizations.value = organizationList
  } finally {
    metaLoading.value = false
  }
}

function buildCreatePayload(): CreateUserPayload {
  return {
    username: form.username.trim(),
    displayName: form.displayName.trim(),
    email: form.email.trim(),
    avatarUrl: form.avatarUrl.trim(),
    password: form.password.trim(),
    enabled: form.enabled,
    roleCodes: [...form.roleCodes],
    organizationIds: [...form.organizationIds],
    defaultOrganizationId: form.defaultOrganizationId,
  }
}

function buildUpdatePayload(): UpdateUserPayload {
  return {
    displayName: form.displayName.trim(),
    email: form.email.trim(),
    avatarUrl: form.avatarUrl.trim(),
    password: form.password.trim() || undefined,
    enabled: form.enabled,
    roleCodes: [...form.roleCodes],
    organizationIds: [...form.organizationIds],
    defaultOrganizationId: form.defaultOrganizationId,
  }
}

async function submitForm() {
  if (userFormInvalid.value) {
    toast.warning('请填写用户信息，并至少选择一个角色和组织')
    return
  }
  submitting.value = true
  try {
    if (editingUserId.value) {
      await updateUser(editingUserId.value, buildUpdatePayload())
      toast.success('用户已更新')
    } else {
      await createUser(buildCreatePayload())
      toast.success('用户已创建')
    }
    dialogOpen.value = false
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

function pickAvatar() {
  fileInput.value?.click()
}

async function onAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  avatarUploading.value = true
  try {
    const response = await uploadUserAvatar(file)
    form.avatarUrl = response.avatarUrl
    toast.success('头像已上传')
  } catch {
    toast.error('头像上传失败')
  } finally {
    avatarUploading.value = false
    input.value = ''
  }
}

function roleNames(user: UserView) {
  return user.roles.map((role) => role.name).join(' / ') || '未分配'
}

function organizationNames(user: UserView) {
  return user.organizations.map((organization) => organization.name).join(' / ') || '未分配'
}

onMounted(async () => {
  await Promise.all([loadMeta(), loadUsers()])
})
</script>

<template>
  <div class="page">
    <PageHeader title="用户" description="管理账号、角色和组织">
      <template #actions>
        <Button v-if="canWrite" variant="primary" @click="openCreateDialog">新建用户</Button>
      </template>
    </PageHeader>

    <SectionCard title="用户列表" flush>
      <DataTable :columns="columns" :data="users" row-key="id" :loading="loading || metaLoading">
        <template #user="{ row }">
          <div class="user-cell">
            <Avatar :src="row.avatarUrl || undefined" :size="36">
              {{ row.displayName.slice(0, 1).toUpperCase() }}
            </Avatar>
            <div>
              <div class="user-cell__name">{{ row.displayName }}</div>
              <div class="user-cell__meta">{{ row.username }}</div>
            </div>
          </div>
        </template>
        <template #email="{ row }">{{ row.email || '未设置' }}</template>
        <template #roles="{ row }">{{ roleNames(row) }}</template>
        <template #organizations="{ row }">{{ organizationNames(row) }}</template>
        <template #enabled="{ row }">
          <Tag :tone="row.enabled ? 'ok' : 'neutral'">{{ row.enabled ? '启用' : '停用' }}</Tag>
        </template>
        <template #actions="{ row }">
          <Button v-if="canWrite" link @click="openEditDialog(row)">编辑</Button>
        </template>
      </DataTable>
    </SectionCard>

    <Dialog v-model:open="dialogOpen" :title="dialogTitle" width="760px">
      <div class="form-grid">
        <FormField v-if="!editingUserId" label="用户名" required>
          <Input v-model="form.username" />
        </FormField>
        <FormField label="姓名" required>
          <Input v-model="form.displayName" />
        </FormField>
        <FormField label="邮箱">
          <Input v-model="form.email" />
        </FormField>
        <FormField label="状态">
          <Switch v-model="form.enabled" />
        </FormField>
        <FormField :label="editingUserId ? '重置密码' : '密码'" :required="!editingUserId">
          <Input v-model="form.password" type="password" show-password />
        </FormField>
        <FormField label="默认组织" required>
          <Select
            v-model="form.defaultOrganizationId"
            :options="defaultOrganizationOptions"
            placeholder="选择默认组织"
          />
        </FormField>
        <FormField label="角色" required>
          <MultiSelect v-model="form.roleCodes" :options="roleOptions" placeholder="选择角色" />
        </FormField>
        <FormField label="组织" required>
          <MultiSelect
            v-model="form.organizationIds"
            :options="organizationOptions"
            placeholder="选择组织"
            @change="syncDefaultOrganization"
          />
        </FormField>
        <FormField label="头像地址">
          <Input v-model="form.avatarUrl" placeholder="可直接填写图片地址" />
        </FormField>
        <FormField label="上传头像" hint="支持 PNG、JPG、WEBP，大小不超过 2 MB">
          <input
            ref="fileInput"
            type="file"
            accept="image/png,image/jpeg,image/webp"
            hidden
            @change="onAvatarChange"
          />
          <Button :loading="avatarUploading" @click="pickAvatar">选择图片</Button>
        </FormField>
      </div>

      <div v-if="form.avatarUrl" class="avatar-preview">
        <Avatar :src="form.avatarUrl" :size="64">
          {{ (form.displayName || form.username || '用').slice(0, 1).toUpperCase() }}
        </Avatar>
        <span>头像预览</span>
      </div>

      <template #footer>
        <Button @click="dialogOpen = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="userFormInvalid" @click="submitForm">
          保存
        </Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: 11px;
}
.user-cell__name {
  font-weight: 500;
}
.user-cell__meta {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 1px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.avatar-preview {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  font-size: 13px;
  color: var(--text-3);
}
@media (max-width: 680px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
