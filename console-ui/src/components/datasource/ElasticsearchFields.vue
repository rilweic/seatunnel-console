<script setup lang="ts">
/** Connection fields of Elasticsearch; property names match ElasticsearchDataSourcePlugin. */
defineProps<{
  values: Record<string, string>
  secretPlaceholder: (key: string) => string
}>()
</script>

<template>
  <el-form-item label="地址" required>
    <el-input v-model="values.hosts" placeholder="http://es1:9200,http://es2:9200" autocomplete="off" />
  </el-form-item>
  <el-form-item label="用户名">
    <el-input v-model="values.username" placeholder="可选" autocomplete="off" />
  </el-form-item>
  <el-form-item label="密码">
    <el-input v-model="values.password" type="password" show-password :placeholder="secretPlaceholder('password')" autocomplete="off" />
  </el-form-item>
</template>
