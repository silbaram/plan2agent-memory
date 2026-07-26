import type { DashboardArtifactType } from '../data-access'

export const supportedArtifactTypes = [
  'DOCUMENT_SNAPSHOT',
  'TASK_GRAPH',
  'TASK',
  'RUN_RECORD',
  'PROPOSAL',
] as const satisfies readonly DashboardArtifactType[]

export function isSupportedArtifactType(value: string): value is DashboardArtifactType {
  return (supportedArtifactTypes as readonly string[]).includes(value)
}
