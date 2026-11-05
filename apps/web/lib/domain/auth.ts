import type { User } from "@/lib/domain/shared"

export interface AuthenticatedUser extends User {
  effectivePermissions: string[]
}

export interface LoginCredentials {
  email: string
  password: string
}

export interface CredentialAction {
  token: string
  password: string
}
