import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createBffServer } from './server'

const dashboardDistDir = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'dist')
const localToken = requiredEnvironmentValue('P2A_LOCAL_TOKEN')
const upstreamOrigin = requiredEnvironmentValue('P2A_BFF_UPSTREAM_ORIGIN')
const host = process.env.P2A_BFF_HOST ?? '0.0.0.0'
const port = environmentPort('P2A_BFF_PORT', 4173)
const server = createBffServer({
  distDir: dashboardDistDir,
  localToken,
  upstreamOrigin,
})

async function startServer() {
  try {
    await server.listen({ host, port })
  } catch {
    await server.close()
    console.error('Dashboard BFF failed to start')
    process.exitCode = 1
  }
}

async function stopServer() {
  await server.close()
}

process.once('SIGINT', () => {
  void stopServer()
})
process.once('SIGTERM', () => {
  void stopServer()
})

void startServer()

function requiredEnvironmentValue(name: string) {
  const value = process.env[name]
  if (value === undefined || value.length === 0) {
    throw new Error(`${name} must be set`)
  }
  return value
}

function environmentPort(name: string, defaultValue: number) {
  const value = process.env[name]
  if (value === undefined) {
    return defaultValue
  }
  if (!/^[1-9][0-9]{0,4}$/.test(value)) {
    throw new Error(`${name} must be a valid TCP port`)
  }

  const port = Number(value)
  if (port > 65_535) {
    throw new Error(`${name} must be a valid TCP port`)
  }
  return port
}
