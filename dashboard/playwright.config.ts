import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: './node_modules/.bin/tsc --noEmit && ./node_modules/.bin/vite build && ./node_modules/.bin/vite build --ssr src/bff/bootstrap.ts --outDir build/server && P2A_BFF_HOST=127.0.0.1 P2A_BFF_PORT=4173 P2A_BFF_UPSTREAM_ORIGIN=http://127.0.0.1:8080 P2A_LOCAL_TOKEN=playwright-server-only-token node build/server/bootstrap.js',
    port: 4173,
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
