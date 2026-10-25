import type { Component } from 'vue'

import ElasticsearchFields from './ElasticsearchFields.vue'
import HttpFields from './HttpFields.vue'
import JdbcFields from './JdbcFields.vue'

/** How the datasource dialog edits one type's connection properties. */
export interface DatasourceForm {
  component: Component
  /** Extra props for the component, e.g. a JDBC database's default port. */
  options?: Record<string, unknown>
  /** Properties a new datasource of this type starts with. */
  initialProps?: () => Record<string, string>
}

/**
 * One entry per datasource type (design.md §7.14). A new JDBC database reuses JdbcFields with its
 * own default port; a type without an entry here cannot be created on the datasource page.
 */
export const datasourceForms: Record<string, DatasourceForm> = {
  MYSQL: {
    component: JdbcFields,
    options: { defaultPort: 3306, databaseRequired: false, paramsPlaceholder: '如 useSSL=false&serverTimezone=Asia/Shanghai' },
  },
  POSTGRESQL: {
    component: JdbcFields,
    options: { defaultPort: 5432, databaseRequired: true, paramsPlaceholder: '如 sslmode=disable' },
  },
  ELASTICSEARCH: { component: ElasticsearchFields },
  HTTP: { component: HttpFields, initialProps: () => ({ authType: 'NONE', apiKeyLocation: 'HEADER' }) },
}
