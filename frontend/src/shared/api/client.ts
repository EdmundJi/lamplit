export type ApiError = { status: number; code: string; message: string; details?: Record<string, unknown> }
type Envelope<T> = { data: T; requestId: string; timestamp: string }

function cookie(name: string) {
  return document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith(`${name}=`))?.slice(name.length + 1)
}

async function request<T>(path: string, init: RequestInit = {}, retried = false): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrf = cookie('csrf_token')
    if (csrf) headers.set('X-CSRF-Token', decodeURIComponent(csrf))
  }
  const response = await fetch(`/api/v1${path}`, { ...init, headers, credentials: 'include' })
  if (response.status === 401 && !retried && path !== '/auth/refresh') {
    const refreshed = await fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include', headers })
    if (refreshed.ok) return request(path, init, true)
  }
  const envelope = await response.json().catch(() => null) as Envelope<T | ApiError> | null
  if (!response.ok) throw (envelope?.data ?? { status: response.status, code: 'REQUEST_FAILED', message: '请求失败，请稍后重试' }) as ApiError
  return envelope!.data as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), headers }),
  patch: <T>(path: string, body: unknown) => request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) => request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
