<script setup lang="ts">
/** embedded：嵌入系统设置页的 Tab 时隐藏自身 PageHeader，避免标题重复 */
defineProps<{ embedded?: boolean }>()
import { computed, onMounted, reactive, ref } from 'vue'
import {
  fetchRagSettings,
  rebuildRagIndex,
  testRagSettings,
  updateRagSettings,
  type RagEmbeddingProvider,
  type SaveRagSettingsPayload,
} from '../api/rag'
import { Button, FormField, Input, NumberInput, PageHeader, SectionCard, Select, Switch, toast, type SelectOption } from '../ui'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const rebuilding = ref(false)
const hasEmbeddingApiKey = ref(false)

const providerOptions: SelectOption[] = [
  { label: '内置本地向量', value: 'LOCAL' },
  { label: 'OpenAI compatible Embedding', value: 'OPENAI_COMPATIBLE' },
]

const form = reactive<SaveRagSettingsPayload>({
  embeddingProvider: 'LOCAL',
  embeddingBaseUrl: '',
  embeddingApiKey: '',
  embeddingModel: 'local-hash-embedding',
  embeddingDimension: 384,
  chunkSize: 800,
  chunkOverlap: 120,
  topK: 5,
  milvusEnabled: false,
  milvusBaseUrl: 'http://localhost:19530',
  milvusCollection: 'kylinops_rag_chunks',
  elasticsearchEnabled: false,
  elasticsearchBaseUrl: 'http://localhost:9200',
  elasticsearchIndex: 'kylinops-rag-chunks',
  rerankerEnabled: false,
  rerankerBaseUrl: '',
  rerankerApiKey: '',
  rerankerModel: '',
  rerankerTopN: 20,
  redisEnabled: false,
  redisBaseUrl: 'localhost:6379',
  redisPrefix: 'kylinops',
})

const formInvalid = computed(() => {
  if (!form.embeddingProvider || !form.embeddingModel.trim()) return true
  if (form.embeddingProvider === 'OPENAI_COMPATIBLE' && !form.embeddingBaseUrl.trim()) return true
  if (form.chunkOverlap >= form.chunkSize) return true
  if (form.embeddingDimension < 16 || form.embeddingDimension > 4096) return true
  if (form.chunkSize < 200 || form.chunkSize > 4000) return true
  if (form.topK < 1 || form.topK > 20) return true
  if (form.rerankerEnabled && !form.rerankerModel.trim()) return true
  if (form.rerankerTopN < 1 || form.rerankerTopN > 100) return true
  return !form.milvusBaseUrl.trim()
    || !form.milvusCollection.trim()
    || !form.elasticsearchBaseUrl.trim()
    || !form.elasticsearchIndex.trim()
    || !form.redisBaseUrl.trim()
    || !form.redisPrefix.trim()
})

async function loadSettings() {
  loading.value = true
  try {
    const settings = await fetchRagSettings()
    form.embeddingProvider = settings.embeddingProvider
    form.embeddingBaseUrl = settings.embeddingBaseUrl
    form.embeddingApiKey = ''
    form.embeddingModel = settings.embeddingModel
    form.embeddingDimension = settings.embeddingDimension
    form.chunkSize = settings.chunkSize
    form.chunkOverlap = settings.chunkOverlap
    form.topK = settings.topK
    form.milvusEnabled = settings.milvusEnabled
    form.milvusBaseUrl = settings.milvusBaseUrl
    form.milvusCollection = settings.milvusCollection
    form.elasticsearchEnabled = settings.elasticsearchEnabled
    form.elasticsearchBaseUrl = settings.elasticsearchBaseUrl
    form.elasticsearchIndex = settings.elasticsearchIndex
    form.rerankerEnabled = settings.rerankerEnabled
    form.rerankerBaseUrl = settings.rerankerBaseUrl
    form.rerankerApiKey = ''
    form.rerankerModel = settings.rerankerModel
    form.rerankerTopN = settings.rerankerTopN
    form.redisEnabled = settings.redisEnabled
    form.redisBaseUrl = settings.redisBaseUrl
    form.redisPrefix = settings.redisPrefix
    hasEmbeddingApiKey.value = settings.hasEmbeddingApiKey
  } finally {
    loading.value = false
  }
}

async function submitSettings() {
  if (formInvalid.value) {
    toast.warning('请补全 RAG 配置，并确认分片参数范围正确')
    return
  }
  saving.value = true
  try {
    await updateRagSettings({
      ...form,
      embeddingBaseUrl: form.embeddingBaseUrl.trim(),
      embeddingApiKey: form.embeddingApiKey.trim(),
      embeddingModel: form.embeddingModel.trim(),
      milvusBaseUrl: form.milvusBaseUrl.trim(),
      milvusCollection: form.milvusCollection.trim(),
      elasticsearchBaseUrl: form.elasticsearchBaseUrl.trim(),
      elasticsearchIndex: form.elasticsearchIndex.trim(),
      redisBaseUrl: form.redisBaseUrl.trim(),
      redisPrefix: form.redisPrefix.trim(),
    })
    form.embeddingApiKey = ''
    toast.success('RAG 设置已保存')
    await loadSettings()
  } finally {
    saving.value = false
  }
}

async function runTest() {
  testing.value = true
  try {
    const result = await testRagSettings()
    toast.success(result.message)
  } finally {
    testing.value = false
  }
}

async function runRebuild() {
  rebuilding.value = true
  try {
    await rebuildRagIndex()
    toast.success('知识库索引已重建')
  } finally {
    rebuilding.value = false
  }
}

function updateProvider(value: string | number) {
  form.embeddingProvider = value as RagEmbeddingProvider
  if (form.embeddingProvider === 'LOCAL') {
    form.embeddingBaseUrl = ''
    form.embeddingModel = 'local-hash-embedding'
  }
}

onMounted(async () => {
  await loadSettings()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader v-if="!embedded" title="RAG 设置" description="配置知识库检索、向量生成和外部索引服务。">
      <template #actions>
        <Button :loading="testing" @click="runTest">测试</Button>
        <Button :loading="rebuilding" @click="runRebuild">重建索引</Button>
        <Button variant="primary" :loading="saving" :disabled="formInvalid" @click="submitSettings">保存</Button>
      </template>
    </PageHeader>

    <!-- 嵌入系统设置页时 PageHeader 被隐藏，操作按钮必须另行给出，否则整页无法保存 -->
    <div v-if="embedded" class="embedded-actions">
      <Button :loading="testing" @click="runTest">测试</Button>
      <Button :loading="rebuilding" @click="runRebuild">重建索引</Button>
      <Button variant="primary" :loading="saving" :disabled="formInvalid" @click="submitSettings">保存</Button>
    </div>

    <SectionCard title="向量与分片">
      <div class="form-grid">
        <FormField label="Embedding 提供商">
          <Select :model-value="form.embeddingProvider" :options="providerOptions" @update:model-value="updateProvider" />
        </FormField>
        <FormField label="Embedding 模型">
          <Input v-model="form.embeddingModel" />
        </FormField>
        <FormField label="Embedding 接口">
          <Input v-model="form.embeddingBaseUrl" :disabled="form.embeddingProvider === 'LOCAL'" placeholder="https://api.example.com/v1" />
        </FormField>
        <FormField label="API Key" :hint="hasEmbeddingApiKey ? '留空表示保持不变' : ''">
          <Input v-model="form.embeddingApiKey" type="password" show-password :disabled="form.embeddingProvider === 'LOCAL'" />
        </FormField>
        <FormField label="向量维度">
          <NumberInput v-model="form.embeddingDimension" :min="16" :max="4096" />
        </FormField>
        <FormField label="召回数量">
          <NumberInput v-model="form.topK" :min="1" :max="20" />
        </FormField>
        <FormField label="分片长度">
          <NumberInput v-model="form.chunkSize" :min="200" :max="4000" :step="100" />
        </FormField>
        <FormField label="分片重叠">
          <NumberInput v-model="form.chunkOverlap" :min="0" :max="1000" :step="20" />
        </FormField>
      </div>
    </SectionCard>

    <SectionCard title="交叉重排" description="RRF 融合只看名次；重排用交叉编码器按查询与正文的真实匹配度精排。服务不可用时自动退回融合顺序。">
      <div class="service-block">
        <div class="service-head">
          <strong>Reranker</strong>
          <Switch v-model="form.rerankerEnabled" />
        </div>
        <div class="form-grid">
          <FormField label="模型">
            <Input v-model="form.rerankerModel" placeholder="例如 Pro/BAAI/bge-reranker-v2-m3" />
          </FormField>
          <FormField label="送入重排的候选数">
            <NumberInput v-model="form.rerankerTopN" :min="1" :max="100" :step="5" />
          </FormField>
          <FormField label="接口地址（留空则用 Embedding 的）">
            <Input v-model="form.rerankerBaseUrl" placeholder="https://api.example.com/v1" />
          </FormField>
          <FormField label="API Key（留空则用 Embedding 的）">
            <Input v-model="form.rerankerApiKey" type="password" placeholder="不修改请留空" />
          </FormField>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="外部索引">
      <div class="service-block">
        <div class="service-head">
          <strong>Milvus</strong>
          <Switch v-model="form.milvusEnabled" />
        </div>
        <div class="form-grid">
          <FormField label="地址"><Input v-model="form.milvusBaseUrl" /></FormField>
          <FormField label="Collection"><Input v-model="form.milvusCollection" /></FormField>
        </div>
      </div>

      <div class="service-block">
        <div class="service-head">
          <strong>Elasticsearch</strong>
          <Switch v-model="form.elasticsearchEnabled" />
        </div>
        <div class="form-grid">
          <FormField label="地址"><Input v-model="form.elasticsearchBaseUrl" /></FormField>
          <FormField label="Index"><Input v-model="form.elasticsearchIndex" /></FormField>
        </div>
      </div>

      <div class="service-block">
        <div class="service-head">
          <strong>Redis</strong>
          <Switch v-model="form.redisEnabled" />
        </div>
        <div class="form-grid">
          <FormField label="地址"><Input v-model="form.redisBaseUrl" /></FormField>
          <FormField label="Key 前缀"><Input v-model="form.redisPrefix" /></FormField>
        </div>
      </div>
    </SectionCard>
  </div>
</template>

<style scoped>
.embedded-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.service-block + .service-block {
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid var(--border);
}
.service-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
@media (max-width: 760px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
