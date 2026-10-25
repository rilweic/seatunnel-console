<script setup lang="ts">
/** Connection fields of a JDBC database; property names match JdbcDataSourcePlugin. */
defineProps<{
  values: Record<string, string>
  secretPlaceholder: (key: string) => string
  defaultPort: number
  /** MySQL can leave the database empty, PostgreSQL connects to one. */
  databaseRequired?: boolean
  paramsPlaceholder?: string
}>()
</script>

<template>
  <el-form-item label="主机" required>
    <el-input v-model="values.host" autocomplete="off" />
  </el-form-item>
  <el-form-item label="端口">
    <el-input v-model="values.port" :placeholder="String(defaultPort)" autocomplete="off" />
  </el-form-item>
  <el-form-item label="数据库" :required="databaseRequired">
    <el-input v-model="values.database" :placeholder="databaseRequired ? '' : '可选'" autocomplete="off" />
  </el-form-item>
  <el-form-item label="用户名" required>
    <el-input v-model="values.username" autocomplete="off" />
  </el-form-item>
  <el-form-item label="密码">
    <el-input v-model="values.password" type="password" show-password :placeholder="secretPlaceholder('password')" autocomplete="off" />
  </el-form-item>
  <el-form-item label="连接参数">
    <el-input v-model="values.params" :placeholder="paramsPlaceholder" autocomplete="off" />
  </el-form-item>
</template>
