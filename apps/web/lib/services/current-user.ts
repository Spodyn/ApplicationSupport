import type { AuthenticatedUser, LoginCredentials } from "@/lib/domain/auth"

export const currentUserQueryKey = ["current-user"] as const

export class AuthenticationRequiredError extends Error {
  constructor() {
    super("Authentication required")
    this.name = "AuthenticationRequiredError"
  }
}

export interface CurrentUserRepository {
  get(): Promise<AuthenticatedUser>
  login(credentials: LoginCredentials): Promise<AuthenticatedUser>
  logout(): Promise<void>
}
