export const ARTIFACT_CONTENT_CHUNK_SIZE = 200_000

export function isMarkdownMediaType(mediaType: string): boolean {
  const normalized = normalizeMediaType(mediaType)
  return normalized === 'text/markdown' || normalized === 'text/x-markdown'
}

export function isJsonMediaType(mediaType: string): boolean {
  const normalized = normalizeMediaType(mediaType)
  return normalized === 'application/json' || normalized === 'text/json' || normalized.endsWith('+json')
}

function normalizeMediaType(mediaType: string): string {
  return mediaType.split(';', 1)[0]?.trim().toLowerCase() ?? ''
}
