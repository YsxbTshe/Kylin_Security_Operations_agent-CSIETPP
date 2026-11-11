<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createOrganization,
  fetchOrganizations,
  updateOrganization,
  type SaveOrganizationPayload,
} from '../api/organizations'
import type { OrganizationView } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  DataTable,
  Dialog,
  FormField,
  Input,
  PageHeader,
  SectionCard,
  Tag,
  toast,
  type DataTableColumn,
} from '../ui'

const authStore = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const dialogOpen = ref(false)
const editingOrganizationId = ref('')
const organizations = ref<OrganizationView[]>([])

const form = reactive({
  code: '',
  name: '',
})

const canWrite = computed(() => authStore.hasPermission('organization:write'))
const dialogTitle = computed(() => (editingOrganizationId.value ? '编辑组织' : '新建组织'))
const organizationFormInvalid = computed(() => {
  if (!form.name.trim()) {
    return true
  }
  return !editingOrganizationId.value && !form.code.trim()
})

const columns = computed<DataTableColumn[]>(() => {
  const base: DataTableColumn[] = [
    { key: 'name', label: '组织名称', minWidth: '220px' },
    { key: 'code', label: '编码', minWidth: '160px' },
    { key: 'hostCount', label: '主机数', width: '120px' },
    { key: 'memberCount', label: '成员数', width: '120px' },
    { key: 'current', label: '当前', width: '120px' },
  ]
  if (canWrite.value) {
    base.push({ key: 'actions', label: '操作', width: '120px' })
  }
  return base
})

function resetForm() {
  editingOrganizationId.value = ''
  form.code = ''
  form.name = ''
}

function openCreateDialog() {
  resetForm()
  dialogOpen.value = true
}

function openEditDialog(organization: OrganizationView) {
  editingOrganizationId.value = organization.id
  form.code = organization.code
  form.name = organization.name
  dialogOpen.value = true
}

async function loadOrganizations() {
  loading.value = true
  try {
    organizations.value = await fetchOrganizations()
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  if (organizationFormInvalid.value) {
    toast.warning('请填写组织名称和编码')
    return
  }

  submitting.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
    } satisfies SaveOrganizationPayload

    if (editingOrganizationId.value) {
      await updateOrganization(editingOrganizationId.value, payload)
      toast.success('组织已更新')
    } else {
      await createOrganization(payload)
      toast.success('组织已创建')
    }
    dialogOpen.value = false
    await loadOrganizations()
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadOrganizations()
})
</script>

<template>
  <div class="page">
    <PageHeader title="组织" description="管理组织。">
      <template #actions>
        <Button v-if="canWrite" variant="primary" @click="openCreateDialog">新建组织</Button>
      </template>
    </PageHeader>

    <SectionCard title="组织列表" flush>
      <DataTable :columns="columns" :data="organizations" row-key="id" :loading="loading">
        <template #hostCount="{ value }">
          <span class="num">{{ value }}</span>
        </template>
        <template #memberCount="{ value }">
          <span class="num">{{ value }}</span>
        </template>
        <template #current="{ row }">
          <Tag v-if="row.current" tone="ok">当前组织</Tag>
        </template>
        <template #actions="{ row }">
          <Button v-if="canWrite" link @click="openEditDialog(row)">编辑</Button>
        </template>
      </DataTable>
    </SectionCard>

    <Dialog v-model:open="dialogOpen" :title="dialogTitle" width="560px">
      <div class="form-grid">
        <FormField label="组织名称" required>
          <Input v-model="form.name" />
        </FormField>
        <FormField label="编码" required>
          <Input v-model="form.code" :disabled="Boolean(editingOrganizationId)" />
        </FormField>
      </div>

      <template #footer>
        <Button @click="dialogOpen = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="organizationFormInvalid" @click="submitForm">
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
@media (max-width: 680px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
