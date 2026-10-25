<script setup lang="ts">
/** Base address, authentication and common headers of an HTTP API; names match HttpDataSourcePlugin. */
defineProps<{
  values: Record<string, string>
  secretPlaceholder: (key: string) => string
}>()
</script>

<template>
  <el-form-item label="基础地址" required>
    <el-input v-model="values.baseUrl" placeholder="https://api.example.com" autocomplete="off" />
  </el-form-item>
  <el-form-item label="连接超时">
    <el-input v-model="values.connectTimeoutMs" placeholder="12000（毫秒）" autocomplete="off" />
  </el-form-item>
  <el-form-item label="读取超时">
    <el-input v-model="values.socketTimeoutMs" placeholder="60000（毫秒）" autocomplete="off" />
  </el-form-item>
  <el-form-item label="测试路径">
    <el-input v-model="values.testPath" placeholder="可选，如 /health" autocomplete="off" />
  </el-form-item>
  <el-form-item label="认证方式">
    <el-select v-model="values.authType">
      <el-option label="无认证" value="NONE" />
      <el-option label="Bearer Token" value="BEARER" />
      <el-option label="Basic" value="BASIC" />
      <el-option label="API Key" value="API_KEY" />
    </el-select>
  </el-form-item>
  <el-form-item v-if="values.authType === 'BEARER'" label="Token" required>
    <el-input v-model="values.token" type="password" show-password :placeholder="secretPlaceholder('token')" autocomplete="off" />
  </el-form-item>
  <template v-if="values.authType === 'BASIC'">
    <el-form-item label="用户名" required><el-input v-model="values.username" autocomplete="off" /></el-form-item>
    <el-form-item label="密码">
      <el-input v-model="values.password" type="password" show-password :placeholder="secretPlaceholder('password')" autocomplete="off" />
    </el-form-item>
  </template>
  <template v-if="values.authType === 'API_KEY'">
    <el-form-item label="Key 位置"><el-radio-group v-model="values.apiKeyLocation"><el-radio-button value="HEADER">Header</el-radio-button><el-radio-button value="QUERY">Query</el-radio-button></el-radio-group></el-form-item>
    <el-form-item label="Key 名称" required><el-input v-model="values.apiKeyName" placeholder="如 X-API-Key 或 api_key" /></el-form-item>
    <el-form-item label="Key 值" required>
      <el-input v-model="values.apiKey" type="password" show-password :placeholder="secretPlaceholder('apiKey')" autocomplete="off" />
    </el-form-item>
  </template>
  <el-form-item label="公共 Header">
    <el-input v-model="values.headers" type="textarea" :rows="3" placeholder='JSON 对象，如 {"Accept":"application/json"}；认证 Header 请在上方配置' />
  </el-form-item>
</template>
