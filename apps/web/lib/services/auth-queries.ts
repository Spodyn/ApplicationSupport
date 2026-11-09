"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import type { CredentialAction, LoginCredentials } from "@/lib/domain/auth"
import { currentUserQueryKey } from "@/lib/services/current-user"
import { serviceRegistry } from "@/lib/services/registry"

export function useLogin() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (credentials: LoginCredentials) =>
      serviceRegistry.currentUser.login(credentials),
    gcTime: 0,
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
      queryClient.clear()
    },
  })
}

export function useAcceptInvitation() {
  return useMutation({
    mutationFn: (action: CredentialAction) =>
      serviceRegistry.currentUser.acceptInvitation(action),
    gcTime: 0,
  })
}

export function useCompletePasswordReset() {
  return useMutation({
    mutationFn: (action: CredentialAction) =>
      serviceRegistry.currentUser.completePasswordReset(action),
    gcTime: 0,
  })
}
