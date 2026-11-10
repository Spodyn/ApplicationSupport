import { translate as t } from "@/lib/i18n/catalog"
import type {
  AuthenticatedUser,
  CredentialAction,
  LoginCredentials,
} from "@/lib/domain/auth"

export const currentUserQueryKey = ["current-user"] as const

export class AuthenticationRequiredError extends Error {
  constructor() {
    super(t("Authentication required"))
    this.name = "AuthenticationRequiredError"
  }
}

export interface CurrentUserRepository {
  get(): Promise<AuthenticatedUser>
  login(credentials: LoginCredentials): Promise<AuthenticatedUser>
  logout(): Promise<void>
  acceptInvitation(action: CredentialAction): Promise<void>
  completePasswordReset(action: CredentialAction): Promise<void>
}
