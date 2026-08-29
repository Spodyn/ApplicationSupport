"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import type { LoginCredentials } from "@/lib/domain/auth"
import { currentUserQueryKey } from "@/lib/services/current-user"
import { serviceRegistry } from "@/lib/services/registry"

export function useLogin() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (credentials: LoginCredentials) =>
      serviceRegistry.currentUser.login(credentials),
    onSuccess: (user) => {
      queryClient.setQueryData(currentUserQueryKey, user)
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => serviceRegistry.currentUser.logout(),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: currentUserQueryKey })
    },
  })
}
