import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('renders the dashboard smoke view', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Plan2Agent 대시보드' })).toBeTruthy()
  })
})
