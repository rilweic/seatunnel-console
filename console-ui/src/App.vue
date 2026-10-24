<script setup lang="ts">
import { textTooltip } from '@/utils/tooltip'

const tableConfig = { tooltipEffect: textTooltip.effect, tooltipOptions: textTooltip }
</script>

<template>
  <el-config-provider :table="tableConfig">
    <router-view />
  </el-config-provider>
</template>
