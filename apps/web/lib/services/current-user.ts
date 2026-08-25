import type { User } from "@/lib/domain/shared"
import { mockCurrentUser } from "@/mocks/users"

export interface CurrentUserRepository {
  get(): Promise<User>
}

export const mockCurrentUserRepository: CurrentUserRepository = {
  async get() {
    return new Promise((resolve) =>
      setTimeout(() => resolve(structuredClone(mockCurrentUser)), 100),
    )
  },
}
