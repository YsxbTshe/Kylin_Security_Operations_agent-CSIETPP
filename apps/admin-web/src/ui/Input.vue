<script setup lang="ts">
import { computed, ref } from 'vue'
import { Eye, EyeOff } from 'lucide-vue-next'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue?: string | number
    type?: string
    placeholder?: string
    disabled?: boolean
    rows?: number
    showPassword?: boolean
  }>(),
  { type: 'text', rows: 3 },
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const reveal = ref(false)
const isTextarea = computed(() => props.type === 'textarea')
const inputType = computed(() =>
  props.type === 'password' && reveal.value ? 'text' : props.type,
)

function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement | HTMLTextAreaElement).value)
}
</script>

<template>
  <div class="field" :class="{ disabled, ta: isTextarea }">
    <textarea
      v-if="isTextarea"
      class="control"
      :rows="rows"
      :value="modelValue as string"
      :placeholder="placeholder"
      :disabled="disabled"
      v-bind="$attrs"
      @input="onInput"
    />
    <template v-else>
      <input
        class="control"
        :type="inputType"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        v-bind="$attrs"
        @input="onInput"
      />
      <button
        v-if="type === 'password' && showPassword"
        type="button"
        class="reveal"
        tabindex="-1"
        @click="reveal = !reveal"
      >
        <component :is="reveal ? EyeOff : Eye" />
      </button>
    </template>
  </div>
</template>

<style scoped>
.field {
  display: flex;
  align-items: center;
  min-height: 36px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: color-mix(in oklch, var(--surface), var(--bg) 26%);
  transition: border-color .15s, box-shadow .15s, background .15s;
}
.field.ta {
  align-items: stretch;
  padding: 8px 11px;
}
.field:focus-within {
  border-color: var(--accent);
  box-shadow: var(--ring);
  background: var(--surface);
}
.field.disabled {
  background: var(--surface-2);
  opacity: 0.7;
}
.control {
  flex: 1;
  width: 100%;
  border: none;
  outline: none;
  background: none;
  color: var(--text);
  font-size: 13.5px;
}
.control::placeholder {
  color: var(--text-3);
}
.ta .control {
  resize: vertical;
  line-height: 1.55;
  padding: 0;
}
.reveal {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  color: var(--text-3);
  cursor: pointer;
}
.reveal:hover {
  color: var(--text-2);
}
.reveal :deep(svg) {
  width: 16px;
  height: 16px;
}
</style>
