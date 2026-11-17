<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchPermissions, type PermissionView } from '../api/permissions'
import { createRole, deleteRole, fetchRoles, updateRole, type SaveRolePayload } from '../api/roles'
import type { RoleView } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  confirm,
  DataTable,
  Dialog,
  FormField,
  Input,
  MultiSelect,
  PageHeader,
  SectionCard,
  Tag,
  toast,
  type DataTableColumn,
  type SelectOption,
} from '../ui'

const authStore = useAuthStore()
const loading = ref(false)
const metaLoading = ref(false)
const submitting = ref(false)
const dialogOpen = ref(false)
const editingRoleCode = ref('')
const roles = ref<RoleView[]>([])
const permissions = ref<PermissionView[]>([])

const form = reactive({
  code: '',
  name: '',
  description: '',
  permissions: [] as string[],
})

const canWrite = computed(() => {
  if (authStore.hasPermission('role:write')) {
    return true
  }
  return authStore.user?.roles.some((role) => role.code === 'platform_admin') ?? false
})
const dialogTitle = computed(() => (editingRoleCode.value ? '编辑角色' : '新建角色'))
const roleFormInvalid = computed(() => {
  if (!form.name.trim() || !form.description.trim() || form.permissions.length === 0) {
    return true
  }
  return !editingRoleCode.value && !form.code.trim()
})

const permissionOptions = computed<SelectOption[]>(() =>
  permissions.value.map((permission) => ({
    label: `${permission.name} · ${permission.code}`,
    value: permission.code,
  })),
)

const columns = computed<DataTableColumn[]>(() => {
  const base: DataTableColumn[] = [
    { key: 'name', label: '角色名称', minWidth: '180px' },
    { key: 'code', label: '编码', minWidth: '160px' },
    { key: 'description', label: '说明', minWidth: '260px' },
    { key: 'permissions', label: '权限', minWidth: '320px' },
  ]
  if (canWrite.value) {
    base.push({ key: 'actions', label: '操作', width: '160px' })
  }
  return base
})

function resetForm() {
  editingRoleCode.value = ''
  form.code = ''
  form.name = ''
  form.description = ''
  form.permissions = []
}

function openCreateDialog() {
  resetForm()
  dialogOpen.value = true
}

function openEditDialog(role: RoleView) {
  editingRoleCode.value = role.code
  form.code = role.code
  form.name = role.name
  form.description = role.description
  form.permissions = [...role.permissions]
  dialogOpen.value = true
}

async function loadData() {
  loading.value = true
  metaLoading.value = true
  try {
    const [roleList, permissionList] = await Promise.all([fetchRoles(), fetchPermissions()])
    roles.value = roleList
    permissions.value = permissionList
  } finally {
    loading.value = false
    metaLoading.value = false
  }
}

async function submitForm() {
  if (roleFormInvalid.value) {
    toast.warning('请填写角色名称、编码、说明并至少选择一个权限')
    return
  }

  submitting.value = true
  try {
    const payload: SaveRolePayload = {
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      permissions: [...form.permissions],
    }

    if (editingRoleCode.value) {
      await updateRole(editingRoleCode.value, {
        name: payload.name,
        description: payload.description,
        permissions: payload.permissions,
      })
      toast.success('角色已更新')
    } else {
      await createRole(payload)
      toast.success('角色已创建')
    }

    dialogOpen.value = false
    await loadData()
  } finally {
    submitting.value = false
  }
}

async function removeRole(role: RoleView) {
  if (
    !(await confirm({
      title: '删除角色',
      message: `确认删除角色“${role.name}”？`,
      tone: 'danger',
      confirmText: '删除',
    }))
  ) {
    return
  }

  await deleteRole(role.code)
  toast.success('角色已删除')
  await loadData()
}

onMounted(async () => {
  await loadData()
})
</script>

<template>
  <div class="page">
    <PageHeader title="角色" description="管理角色和权限集合。">
      <template #actions>
        <Button v-if="canWrite" variant="primary" @click="openCreateDialog">新建角色</Button>
      </template>
    </PageHeader>

    <SectionCard title="角色列表" flush>
      <DataTable :columns="columns" :data="roles" row-key="code" :loading="loading || metaLoading">
        <template #permissions="{ row }">
          <div class="tag-flow">
            <Tag v-for="permission in row.permissions" :key="permission" tone="accent">{{ permission }}</Tag>
          </div>
        </template>
        <template #actions="{ row }">
          <div class="row-actions">
            <Button link @click="openEditDialog(row)">编辑</Button>
            <Button link variant="danger" @click="removeRole(row)">删除</Button>
          </div>
        </template>
      </DataTable>
    </SectionCard>

    <Dialog v-model:open="dialogOpen" :title="dialogTitle" width="680px">
      <div class="form-grid">
        <FormField label="角色名称" required>
          <Input v-model="form.name" />
        </FormField>
        <FormField label="编码" required>
          <Input v-model="form.code" :disabled="Boolean(editingRoleCode)" />
        </FormField>
      </div>

      <div class="form-stack">
        <FormField label="说明" required>
          <Input v-model="form.description" type="textarea" :rows="3" />
        </FormField>

        <FormField label="权限" required>
          <MultiSelect v-model="form.permissions" :options="permissionOptions" placeholder="选择权限" />
        </FormField>
      </div>

      <template #footer>
        <Button @click="dialogOpen = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="roleFormInvalid" @click="submitForm">
          保存
        </Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.tag-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}
@media (max-width: 680px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
