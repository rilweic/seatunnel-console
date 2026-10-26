<script setup lang="ts">
import { computed } from 'vue'

import elasticsearchIcon from '@/assets/datasource/elasticsearch.svg'
import mysqlIcon from '@/assets/datasource/mysql.svg'
import postgresqlIcon from '@/assets/datasource/postgresql.svg'

const props = defineProps<{ type: string }>()

const icons: Record<string, string> = {
  MYSQL: mysqlIcon,
  POSTGRESQL: postgresqlIcon,
  ELASTICSEARCH: elasticsearchIcon,
}

const icon = computed(() => icons[props.type])
</script>

<template>
  <span class="datasource-type-icon" :class="type.toLowerCase()" aria-hidden="true">
    <img v-if="icon" :src="icon" alt="">
    <svg v-else-if="type === 'HTTP'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
      <circle cx="12" cy="12" r="9" />
      <path d="M3.5 9h17M3.5 15h17M12 3c2.2 2.45 3.3 5.45 3.3 9S14.2 18.55 12 21c-2.2-2.45-3.3-5.45-3.3-9S9.8 5.45 12 3z" />
    </svg>
    <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
      <ellipse cx="12" cy="5.5" rx="8" ry="3.5" />
      <path d="M4 5.5v6c0 1.93 3.58 3.5 8 3.5s8-1.57 8-3.5v-6M4 11.5v6c0 1.93 3.58 3.5 8 3.5s8-1.57 8-3.5v-6" />
    </svg>
  </span>
</template>

<style scoped>
.datasource-type-icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 3px;
  border-radius: 6px;
  color: #64748b;
  background: #f1f5f9;
  box-sizing: border-box;
  vertical-align: middle;
}

.datasource-type-icon.mysql {
  background: #e8f6f8;
}

.datasource-type-icon.postgresql {
  background: #edf2ff;
}

.datasource-type-icon.elasticsearch {
  background: #e9f4f6;
}

.datasource-type-icon.http {
  color: #7c3aed;
  background: #f3effd;
}

img,
svg {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
