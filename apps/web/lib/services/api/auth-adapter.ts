import { createApiClient } from "@usi/api-client/generated"
import type { CurrentSession, UserRole as ApiUserRole } from "@usi/api-client/generated"
import type { AuthenticatedUser, LoginCredentials } from "@/lib/domain/auth"
import {
  AuthenticationRequiredError,
  type CurrentUserRepository,
} from "@/lib/services/current-user"
import { browserApiTransport, isUnauthorizedApiError } from "./http-transport"

const authClient = createApiClient(browserApiTransport)

function mapRole(role: ApiUserRole): AuthenticatedUser["role"] {
  switch (role) {
    case "USER":
      return "agent"
    case "ADMIN":
      return "admin"
  }
}

function mapCurrentSession(session: CurrentSession): AuthenticatedUser {
  return {
    id: session.id,
    fullName: session.displayName,
    email: session.email,
    role: mapRole(session.role),
    presence: "online",
    createdAt: session.createdAt,
    effectivePermissions: [...session.effectivePermissions],
  }
}

async function currentSession(): Promise<AuthenticatedUser> {
  try {
    return mapCurrentSession(await authClient.getCurrentSession())
  } catch (error) {
    if (isUnauthorizedApiError(error)) throw new AuthenticationRequiredError()
    throw error
  }
}

async function prepareCsrfCookie(): Promise<void> {
  try {
    await authClient.getCurrentSession()
  } catch (error) {
    if (!isUnauthorizedApiError(error)) throw error
  }
}

export const apiCurrentUserRepository: CurrentUserRepository = {
  get: currentSession,

  async login(credentials: LoginCredentials) {
    await prepareCsrfCookie()
    try {
      return mapCurrentSession(await authClient.login({ body: credentials }))
    } catch (error) {
      if (isUnauthorizedApiError(error)) throw new AuthenticationRequiredError()
      throw error
    }
  },

  async logout() {
    try {
      await authClient.logout()
    } catch (error) {
      if (!isUnauthorizedApiError(error)) throw error
    }
  },
}
