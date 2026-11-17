<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createPermission,
  deletePermission,
  fetchPermissions,
  updatePermission,
  type PermissionView,
  type SavePermissionPayload,
} from '../api/permissions'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  confirm,
  DataTable,
  Dialog,
  FormField,
  Input,
  PageHeader,
  SectionCard,
  toast,
  type DataTableColumn,
} from '../ui'

const authStore = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const dialogOpen = ref(false)
const editingPermissionCode = ref('')
const permissions = ref<PermissionView[]>([])

const form = reactive({
  code: '',
  name: '',
  module: '',
  description: '',
})

const canWrite = computed(() => {
  if (authStore.hasPermission('permission:write')) {
    return true
  }
  return authStore.user?.roles.some((role) => role.code === 'platform_admin') ?? false
})
const dialogTitle = computed(() => (editingPermissionCode.value ? '编辑权限' : '新建权限'))
const permissionFormInvalid = computed(() => {
  if (!form.name.trim() || !form.module.trim() || !form.description.trim()) {
    return true
  }
  return !editingPermissionCode.value && !form.code.trim()
})

const columns = computed<DataTableColumn[]>(() => {
  const base: DataTableColumn[] = [
    { key: 'name', label: '权限名称', minWidth: '180px' },
    { key: 'code', label: '编码', minWidth: '200px' },
    { key: 'module', label: '模块', width: '140px' },
    { key: 'description', label: '说明', minWidth: '260px' },
  ]
  if (canWrite.value) {
    base.push({ key: 'actions', label: '操作', width: '160px' })
  }
  return base
})

function resetForm() {
  editingPermissionCode.value = ''
  form.code = ''
  form.name = ''
  form.module = ''
  form.description = ''
}

function openCreateDialog() {
  resetForm()
  dialogOpen.value = true
}

function openEditDialog(permission: PermissionView) {
  editingPermissionCode.value = permission.code
  form.code = permission.code
  form.name = permission.name
  form.module = permission.module
  form.description = permission.description
  dialogOpen.value = true
}

async function loadPermissions() {
  loading.value = true
  try {
    permissions.value = await fetchPermissions()
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  if (permissionFormInvalid.value) {
    toast.warning('请填写权限名称、编码、模块和说明')
    return
  }

  submitting.value = true
  try {
    const payload: SavePermissionPayload = {
      code: form.code.trim(),
      name: form.name.trim(),
      module: form.module.trim(),
      description: form.description.trim(),
    }

    if (editingPermissionCode.value) {
      await updatePermission(editingPermissionCode.value, {
        name: payload.name,
        module: payload.module,
        description: payload.description,
      })
      toast.success('权限已更新')
    } else {
      await createPermission(payload)
      toast.success('权限已创建')
    }

    dialogOpen.value = false
    await loadPermissions()
  } finally {
    submitting.value = false
  }
}

async function removePermission(permission: PermissionView) {
  if (
    !(await confirm({
      title: '删除权限',
      message: `确认删除权限“${permission.name}”？`,
      tone: 'danger',
      confirmText: '删除',
    }))
  ) {
    return
  }

  await deletePermission(permission.code)
  toast.success('权限已删除')
  await loadPermissions()
}

onMounted(async () => {
  await loadPermissions()
})
</script>

<template>
  <div class="page">
    <PageHeader title="权限节点" description="管理系统权限节点。">
      <template #actions>
        <Button v-if="canWrite" variant="primary" @click="openCreateDialog">新建权限</Button>
      </template>
    </PageHeader>

    <SectionCard title="权限列表" flush>
      <DataTable :columns="columns" :data="permissions" row-key="code" :loading="loading">
        <template #actions="{ row }">
          <div class="row-actions">
            <Button link @click="openEditDialog(row)">编辑</Button>
            <Button link variant="danger" @click="removePermission(row)">删除</Button>
          </div>
        </template>
      </DataTable>
    </SectionCard>

    <Dialog v-model:open="dialogOpen" :title="dialogTitle" width="640px">
      <div class="form-grid">
        <FormField label="权限名称" required>
          <Input v-model="form.name" />
        </FormField>
        <FormField label="编码" required>
          <Input v-model="form.code" :disabled="Boolean(editingPermissionCode)" />
        </FormField>
        <FormField label="模块" required>
          <Input v-model="form.module" />
        </FormField>
        <FormField label="说明" required>
          <Input v-model="form.description" />
        </FormField>
      </div>

      <template #footer>
        <Button @click="dialogOpen = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="permissionFormInvalid" @click="submitForm">
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
