<script setup lang="ts">
/** Edits a list of name/value pairs; turned into a map by the caller. */
export interface ParamRow {
  key: string
  value: string
}

const rows = defineModel<ParamRow[]>({ required: true })

defineProps<{ placeholder?: string }>()
</script>

<template>
  <div class="params">
    <div v-for="(row, i) in rows" :key="i" class="row">
      <el-input v-model="row.key" placeholder="名称，如 dt" class="key" />
      <el-input v-model="row.value" :placeholder="placeholder ?? '值，如 ${system.biz.date} 或 $[yyyyMMdd-1]'" class="value" />
      <el-button link type="danger" @click="rows.splice(i, 1)">删除</el-button>
    </div>
    <el-button size="small" @click="rows.push({ key: '', value: '' })">添加参数</el-button>
  </div>
</template>

<style scoped>
.params {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.key {
  width: 200px;
}

.value {
  flex: 1;
  min-width: 200px;
}

.params > .el-button {
  align-self: flex-start;
}
</style>
