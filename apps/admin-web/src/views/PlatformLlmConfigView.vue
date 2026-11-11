<script setup lang="ts">
/** embedded：嵌入系统设置页的 Tab 时隐藏自身 PageHeader，避免标题重复 */
defineProps<{ embedded?: boolean }>()
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createPlatformLlmConfig,
  deletePlatformLlmConfig,
  fetchPlatformLlmConfigs,
  testPlatformLlmConfig,
  updatePlatformLlmConfig,
  type LlmModelConfigView,
  type LlmProviderType,
  type SaveLlmModelConfigPayload,
} from '../api/platform-llm'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  DataTable,
  Dialog,
  FormField,
  Input,
  NumberInput,
  PageHeader,
  SectionCard,
  Select,
  Switch,
  Tag,
  confirm,
  toast,
  type DataTableColumn,
  type SelectOption,
} from '../ui'

const authStore = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const testingConfigId = ref('')
const dialogVisible = ref(false)
const editingConfigId = ref('')
const editingConfigHasApiKey = ref(false)
const configs = ref<LlmModelConfigView[]>([])

const providerOptions: Array<{ label: string; value: LlmProviderType; baseUrl: string }> = [
  { label: 'DeepSeek', value: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com/v1' },
  { label: 'OpenAI compatible', value: 'OPENAI_COMPATIBLE', baseUrl: '' },
]

const providerSelectOptions: SelectOption[] = providerOptions.map((item) => ({
  label: item.label,
  value: item.value,
}))

const form = reactive<SaveLlmModelConfigPayload>({
  providerType: 'DEEPSEEK',
  groupName: '默认分组',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
  model: '',
  enabled: false,
  timeoutMs: 15000,
  retryCount: 0,
  thinkingLevels: 'OFF',
  thinkingDefault: 'OFF',
})

// 思考档位每模型自定义:逗号分隔的有序 token(Faster→Smarter),如 off,low,high,max。
const parsedThinkingLevels = computed(() => {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of (form.thinkingLevels ?? '').split(',')) {
    const token = raw.trim()
    if (token && !seen.has(token.toLowerCase())) {
      seen.add(token.toLowerCase())
      out.push(token)
    }
  }
  return out
})
const thinkingDefaultOptions = computed(() =>
  (parsedThinkingLevels.value.length ? parsedThinkingLevels.value : ['off']).map((token) => ({
    label: token,
    value: token,
  })),
)

const canConfigure = computed(() => authStore.hasPermission('platform:configure'))
const dialogTitle = computed(() => editingConfigId.value ? '编辑模型' : '添加模型')
const configFormInvalid = computed(() => {
  if (!form.groupName.trim() || !form.model.trim()) {
    return true
  }
  if (form.providerType === 'OPENAI_COMPATIBLE' && !form.baseUrl.trim()) {
    return true
  }
  if (form.enabled && !editingConfigHasApiKey.value && !form.apiKey.trim()) {
    return true
  }
  if (Number(form.timeoutMs) < 1 || Number(form.timeoutMs) > 120000) {
    return true
  }
  return Number(form.retryCount) < 0 || Number(form.retryCount) > 5
})
const groupedConfigs = computed(() => {
  const groups = new Map<string, LlmModelConfigView[]>()
  for (const item of configs.value) {
    const key = item.groupName || '未分组'
    const existing = groups.get(key) ?? []
    existing.push(item)
    groups.set(key, existing)
  }
  return Array.from(groups.entries()).map(([groupName, items]) => ({ groupName, items }))
})
const baseUrlReadonly = computed(() => form.providerType !== 'OPENAI_COMPATIBLE')

const columns: DataTableColumn[] = [
  { key: 'model', label: '模型名', minWidth: '180px' },
  { key: 'provider', label: '提供商', minWidth: '120px' },
  { key: 'baseUrl', label: '接口地址', minWidth: '260px' },
  { key: 'timeoutMs', label: '超时(ms)', width: '120px' },
  { key: 'retryCount', label: '重试', width: '90px' },
  { key: 'enabled', label: '默认模型', width: '100px' },
  { key: 'apiKey', label: '密钥', width: '180px' },
  { key: 'actions', label: '操作', width: '190px' },
]

function resetForm() {
  editingConfigId.value = ''
  editingConfigHasApiKey.value = false
  form.providerType = 'DEEPSEEK'
  form.groupName = '默认分组'
  form.baseUrl = 'https://api.deepseek.com/v1'
  form.apiKey = ''
  form.model = ''
  form.enabled = false
  form.timeoutMs = 15000
  form.retryCount = 0
  form.thinkingLevels = 'OFF'
  form.thinkingDefault = 'OFF'
}

function syncBaseUrl(providerType: LlmProviderType) {
  const provider = providerOptions.find((item) => item.value === providerType)
  if (!provider) {
    return
  }
  if (provider.value !== 'OPENAI_COMPATIBLE') {
    form.baseUrl = provider.baseUrl
  }
}

function openCreateDialog() {
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(config: LlmModelConfigView) {
  editingConfigId.value = config.id
  editingConfigHasApiKey.value = config.hasApiKey
  form.providerType = config.providerType === 'CUSTOM' ? 'OPENAI_COMPATIBLE' : config.providerType
  form.groupName = config.groupName
  form.baseUrl = config.baseUrl
  form.apiKey = ''
  form.model = config.model
  form.enabled = config.enabled
  form.timeoutMs = config.timeoutMs
  form.retryCount = config.retryCount
  form.thinkingLevels = (config.supportedThinkingLevels ?? ['OFF']).join(',')
  form.thinkingDefault = config.defaultThinkingLevel ?? 'OFF'
  dialogVisible.value = true
}

async function loadConfigs() {
  if (!canConfigure.value) {
    configs.value = []
    return
  }
  loading.value = true
  try {
    configs.value = await fetchPlatformLlmConfigs()
  } finally {
    loading.value = false
  }
}

async function submitConfig() {
  if (configFormInvalid.value) {
    toast.warning('请补全模型配置，并确认超时和重试次数在允许范围内')
    return
  }

  submitting.value = true
  try {
    const payload: SaveLlmModelConfigPayload = {
      providerType: form.providerType,
      groupName: form.groupName.trim(),
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim(),
      model: form.model.trim(),
      enabled: form.enabled,
      timeoutMs: Number(form.timeoutMs),
      retryCount: Number(form.retryCount),
      thinkingLevels: form.thinkingLevels,
      thinkingDefault: form.thinkingDefault,
    }

    if (editingConfigId.value) {
      await updatePlatformLlmConfig(editingConfigId.value, payload)
      toast.success('模型已更新')
    } else {
      await createPlatformLlmConfig(payload)
      toast.success('模型已添加')
    }

    dialogVisible.value = false
    await loadConfigs()
  } finally {
    submitting.value = false
  }
}

async function removeConfig(config: LlmModelConfigView) {
  if (!(await confirm({ title: '删除模型', message: `确认删除模型“${config.model}”？`, tone: 'danger', confirmText: '删除' }))) {
    return
  }
  await deletePlatformLlmConfig(config.id)
  toast.success('模型已删除')
  await loadConfigs()
}

async function testConfig(config: LlmModelConfigView) {
  testingConfigId.value = config.id
  try {
    const result = await testPlatformLlmConfig(config.id)
    toast.success(`模型连通正常：${result.message}`)
  } finally {
    testingConfigId.value = ''
  }
}

function providerLabel(providerType: LlmProviderType) {
  if (providerType === 'CUSTOM') {
    return 'OpenAI compatible'
  }
  return providerOptions.find((item) => item.value === providerType)?.label ?? providerType
}

onMounted(async () => {
  await loadConfigs()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader v-if="!embedded" title="模型配置" description="管理平台使用的模型。">
      <template #actions>
        <Button v-if="canConfigure" variant="primary" @click="openCreateDialog">添加模型</Button>
      </template>
    </PageHeader>

    <!-- 同上：嵌入模式下补回操作入口 -->
    <div v-if="embedded && canConfigure" class="embedded-actions">
      <Button variant="primary" @click="openCreateDialog">添加模型</Button>
    </div>

    <template v-if="canConfigure">
      <SectionCard
        v-for="group in groupedConfigs"
        :key="group.groupName"
        :title="group.groupName"
        :description="`${group.items.length} 个模型`"
        flush
      >
        <DataTable :columns="columns" :data="group.items" row-key="id">
          <template #provider="{ row }">{{ providerLabel(row.providerType) }}</template>
          <template #timeoutMs="{ value }"><span class="num">{{ value }}</span></template>
          <template #retryCount="{ value }"><span class="num">{{ value }}</span></template>
          <template #enabled="{ row }">
            <Tag :tone="row.enabled ? 'ok' : 'neutral'">{{ row.enabled ? '是' : '否' }}</Tag>
          </template>
          <template #apiKey="{ row }">{{ row.hasApiKey ? row.apiKeyMasked : '未配置' }}</template>
          <template #actions="{ row }">
            <div class="row-actions">
              <Button
                link
                :loading="testingConfigId === row.id"
                :disabled="!row.enabled || !row.hasApiKey"
                @click="testConfig(row)"
              >
                测试
              </Button>
              <Button link @click="openEditDialog(row)">编辑</Button>
              <Button link variant="danger" @click="removeConfig(row)">删除</Button>
            </div>
          </template>
        </DataTable>
      </SectionCard>

      <SectionCard v-if="groupedConfigs.length === 0" title="模型列表" flush>
        <DataTable :columns="columns" :data="[]" empty="暂无模型配置" />
      </SectionCard>
    </template>

    <SectionCard v-else title="模型列表" flush>
      <DataTable :columns="columns" :data="[]" empty="当前账号没有模型配置权限。" />
    </SectionCard>

    <Dialog v-model:open="dialogVisible" :title="dialogTitle" width="720px">
      <div class="dialog-form">
        <div class="form-grid">
          <FormField label="提供商">
            <Select :model-value="form.providerType" :options="providerSelectOptions"
              @update:model-value="(value) => { form.providerType = value as LlmProviderType; syncBaseUrl(form.providerType) }" />
          </FormField>
          <FormField label="分组">
            <Input v-model="form.groupName" placeholder="例如：默认分组 / 安全分析 / 审计" />
          </FormField>
          <FormField label="模型名">
            <Input v-model="form.model" placeholder="例如：deepseek-chat / qwen-max / glm-4-plus" />
          </FormField>
          <FormField label="默认模型">
            <Switch v-model="form.enabled" />
          </FormField>
        </div>

        <FormField label="接口地址">
          <Input v-model="form.baseUrl" :disabled="baseUrlReadonly" placeholder="OpenAI compatible 模式下填写" />
        </FormField>

        <div class="form-grid">
          <FormField label="API Key" hint="留空表示保持不变">
            <Input v-model="form.apiKey" type="password" show-password />
          </FormField>
          <FormField label="超时(ms)">
            <NumberInput v-model="form.timeoutMs" :min="1000" :step="1000" />
          </FormField>
          <FormField label="重试次数">
            <NumberInput v-model="form.retryCount" :min="0" :step="1" />
          </FormField>
        </div>

        <FormField label="思考等级" hint="逗号分隔的有序档位 token(Faster→Smarter),按该模型实际支持填写,如 off,low,high,max。token off 表示不思考,其余作为 reasoning_effort 下发。">
          <div class="thinking-levels">
            <Input v-model="form.thinkingLevels" placeholder="off,low,high,max" style="max-width: 320px" />
            <span class="thinking-levels__default">
              默认
              <Select v-model="form.thinkingDefault" :options="thinkingDefaultOptions" style="width: 120px" />
            </span>
          </div>
        </FormField>
      </div>

      <template #footer>
        <Button @click="dialogVisible = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="configFormInvalid" @click="submitConfig">保存</Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.embedded-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.row-actions {
  display: flex;
  gap: 12px;
}
@media (max-width: 680px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}

.thinking-levels {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.thinking-levels__pill {
  padding: 4px 14px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text-2);
  cursor: pointer;
  font-size: 13px;
}
.thinking-levels__pill--on {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.thinking-levels__default {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 8px;
  font-size: 13px;
  color: var(--text-3);
}
</style>
