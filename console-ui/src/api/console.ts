/**
 * Barrel re-export of domain API modules for backwards compatibility.
 * New code may import directly from '@/api/auth', '@/api/projects', '@/api/datasources',
 * '@/api/jobs', '@/api/instances', '@/api/ops', '@/api/clusters', or '@/api/retention'.
 */
export * from './auth'
export * from './datasources'
export * from './instances'
export * from './jobs'
export * from './ops'
export * from './projects'
