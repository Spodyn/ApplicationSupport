import type {
  ApiProblem,
  ApiTransport,
  ApiTransportRequest,
} from "@usi/api-client/generated"

export const API_AUTHENTICATION_REQUIRED_EVENT = "usi:api-authentication-required"

export class ApiHttpError extends Error {
  readonly status: number
  readonly problem?: ApiProblem

  constructor(status: number, problem?: ApiProblem) {
    super(problem?.detail ?? `API request failed with status ${status}`)
    this.name = "ApiHttpError"
    this.status = status
    this.problem = problem
  }
}

export function isUnauthorizedApiError(error: unknown): error is ApiHttpError {
  return error instanceof ApiHttpError && error.status === 401
}

export function isForbiddenApiError(error: unknown): error is ApiHttpError {
  return error instanceof ApiHttpError && error.status === 403
}

function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined

  for (const part of document.cookie.split(";")) {
    const [rawName, ...rawValue] = part.trim().split("=")
    if (rawName === name) {
      return decodeURIComponent(rawValue.join("="))
    }
  }
  return undefined
}

function requestPath(request: ApiTransportRequest): string {
  if (!request.query) return request.path

  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(request.query)) {
    if (value !== undefined && value !== null) query.set(name, String(value))
  }
  const suffix = query.toString()
  return suffix ? `${request.path}?${suffix}` : request.path
}

async function readProblem(response: Response): Promise<ApiProblem | undefined> {
  const contentType = response.headers.get("content-type") ?? ""
  if (!contentType.includes("json")) return undefined

  try {
    return (await response.json()) as ApiProblem
  } catch {
    return undefined
  }
}

function notifyAuthenticationRequired(): void {
  if (typeof window === "undefined") return
  window.dispatchEvent(new Event(API_AUTHENTICATION_REQUIRED_EVENT))
}

export const browserApiTransport: ApiTransport = {
  async request<TResponse>(request: ApiTransportRequest): Promise<TResponse> {
    const headers = new Headers({ Accept: "application/json" })
    if (request.body !== undefined) headers.set("Content-Type", "application/json")

    if (!["GET"].includes(request.method)) {
      const csrfToken = readCookie("XSRF-TOKEN")
      if (csrfToken) headers.set("X-XSRF-TOKEN", csrfToken)
    }

    const response = await fetch(requestPath(request), {
      method: request.method,
      headers,
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      credentials: "same-origin",
      cache: "no-store",
    })

    if (!response.ok) {
      const error = new ApiHttpError(response.status, await readProblem(response))
      if (error.status === 401) notifyAuthenticationRequired()
      throw error
    }

    if (response.status === 204) return undefined as TResponse
    return (await response.json()) as TResponse
  },
}
