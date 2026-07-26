import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './mswServer'

const createMatchMedia = (query: string): MediaQueryList => ({
  addEventListener: () => {},
  addListener: () => {},
  dispatchEvent: () => false,
  matches: false,
  media: query,
  onchange: null,
  removeEventListener: () => {},
  removeListener: () => {},
})

class ResizeObserverStub {
  disconnect() {}

  observe() {}

  unobserve() {}
}

const canvasContext = {
  arc: () => {},
  beginPath: () => {},
  globalAlpha: 1,
  lineCap: 'round',
  lineWidth: 0,
  stroke: () => {},
  strokeStyle: '',
}

Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: createMatchMedia,
  writable: true,
})

Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  value: ResizeObserverStub,
  writable: true,
})

Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
  configurable: true,
  value: () => canvasContext,
  writable: true,
})

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  server.resetHandlers()
})

afterAll(() => {
  server.close()
})
