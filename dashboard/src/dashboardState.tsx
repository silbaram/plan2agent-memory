import { Banner } from '@astryxdesign/core/Banner'
import { Card } from '@astryxdesign/core/Card'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Spinner } from '@astryxdesign/core/Spinner'
import { Text } from '@astryxdesign/core/Text'

interface StatePanelProps {
  readonly description: string
  readonly title: string
}

export function LoadingState({ description, title }: StatePanelProps) {
  return (
    <section aria-busy="true" className="dashboard-state">
      <Card padding={6} variant="muted">
        <Spinner label={title} size="lg" />
        <Text as="p" color="secondary" type="supporting">
          {description}
        </Text>
      </Card>
    </section>
  )
}

export function EmptyStatePanel({ description, title }: StatePanelProps) {
  return (
    <section aria-label={title} className="dashboard-state">
      <Card padding={6} variant="muted">
        <EmptyState description={description} headingLevel={2} title={title} />
      </Card>
    </section>
  )
}

export function ErrorState({ description, title }: StatePanelProps) {
  return (
    <section aria-label={title} className="dashboard-state">
      <Banner description={description} status="error" title={title} />
    </section>
  )
}

export function DegradedState({ description, title }: StatePanelProps) {
  return (
    <section aria-label={title} className="dashboard-state">
      <Banner description={description} status="warning" title={title} />
    </section>
  )
}
