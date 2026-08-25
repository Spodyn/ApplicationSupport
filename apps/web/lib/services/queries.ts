"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { serviceRegistry } from "./registry"
import type {
  InboxAskInput,
  InboxIgnoreInput,
  InboxResolveInput,
  InboxSendInput,
} from "./inbox"
import type {
  AdministrationSettings,
  AdministrationUserInput,
  ManagedIntegration,
} from "@/lib/domain/administration"
import type { AdministrationUserQuery } from "./administration"
import type { AnalyticsFilters } from "@/lib/domain/analytics"

export type AdministrationSectionInput = {
  [K in keyof AdministrationSettings]: {
    key: K
    value: AdministrationSettings[K]
  }
}[keyof AdministrationSettings]

/**
 * Klucze zapytań TanStack Query. Ustrukturyzowane tak, aby łatwo było je
 * unieważniać po podłączeniu prawdziwych mutacji z backendu.
 */
export const queryKeys = {
  currentUser: () => ["current-user"] as const,
  inboxCases: () => ["support-inbox", "cases"] as const,
  inboxMessages: (caseId: string) => ["support-inbox", "messages", caseId] as const,
  administrationUsers: (query?: AdministrationUserQuery) =>
    ["administration", "users", query ?? {}] as const,
  currentAdministrationUser: () => ["administration", "current-user"] as const,
  administrationSettings: () => ["administration", "settings"] as const,
  analytics: (filters: AnalyticsFilters) => ["analytics", filters] as const,
}

export function useCurrentUser() {
  return useQuery({
    queryKey: queryKeys.currentUser(),
    queryFn: () => serviceRegistry.currentUser.get(),
  })
}

export function useInboxCases() {
  return useQuery({
    queryKey: queryKeys.inboxCases(),
    queryFn: () => serviceRegistry.inbox.list(),
  })
}

export function useInboxMessages(caseId?: string) {
  return useQuery({
    queryKey: queryKeys.inboxMessages(caseId ?? ""),
    queryFn: () => serviceRegistry.inbox.getMessages(caseId ?? ""),
    enabled: Boolean(caseId),
  })
}

export function useMarkInboxCaseRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (caseId: string) => serviceRegistry.inbox.markRead(caseId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() }),
  })
}

export function useInboxWorkflow(caseId?: string) {
  const queryClient = useQueryClient()
  const requireCaseId = () => {
    if (!caseId) throw new Error("Nie wybrano case’u.")
    return caseId
  }
  const invalidateCases = () =>
    queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() })
  const invalidateConversation = async () => {
    await Promise.all([
      invalidateCases(),
      queryClient.invalidateQueries({
        queryKey: queryKeys.inboxMessages(caseId ?? ""),
      }),
    ])
  }

  const claim = useMutation({
    mutationFn: () => serviceRegistry.inbox.claim(requireCaseId()),
    onSuccess: invalidateConversation,
  })
  const ignore = useMutation({
    mutationFn: (input: InboxIgnoreInput) =>
      serviceRegistry.inbox.ignore(requireCaseId(), input),
    onSuccess: invalidateConversation,
  })
  const askCustomer = useMutation({
    mutationFn: (input: InboxAskInput) =>
      serviceRegistry.inbox.askCustomer(requireCaseId(), input),
    onSuccess: invalidateConversation,
  })
  const resolve = useMutation({
    mutationFn: (input: InboxResolveInput) =>
      serviceRegistry.inbox.resolve(requireCaseId(), input),
    onSuccess: invalidateConversation,
  })
  const snooze = useMutation({
    mutationFn: (until: string) =>
      serviceRegistry.inbox.snooze(requireCaseId(), until),
    onSuccess: invalidateCases,
  })
  const sendMessage = useMutation({
    mutationFn: (input: InboxSendInput) =>
      serviceRegistry.inbox.sendMessage(requireCaseId(), input),
    onSuccess: invalidateConversation,
  })

  return { claim, ignore, askCustomer, resolve, snooze, sendMessage }
}

export function useMarkAllResolvedRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => serviceRegistry.inbox.markAllResolvedRead(),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() }),
  })
}

export function useAdministrationUsers(query?: AdministrationUserQuery) {
  return useQuery({
    queryKey: queryKeys.administrationUsers(query),
    queryFn: () => serviceRegistry.administrationUsers.list(query),
  })
}

export function useCurrentAdministrationUser() {
  return useQuery({
    queryKey: queryKeys.currentAdministrationUser(),
    queryFn: () => serviceRegistry.administrationUsers.getCurrent(),
  })
}

export function useAdministrationUserActions() {
  const queryClient = useQueryClient()
  const invalidate = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ["administration", "users"] }),
      queryClient.invalidateQueries({
        queryKey: queryKeys.currentAdministrationUser(),
      }),
    ])

  const save = useMutation({
    mutationFn: ({ input, id }: { input: AdministrationUserInput; id?: string }) =>
      serviceRegistry.administrationUsers.save(input, id),
    onSuccess: invalidate,
  })
  const deactivate = useMutation({
    mutationFn: (id: string) => serviceRegistry.administrationUsers.deactivate(id),
    onSuccess: invalidate,
  })
  const deleteUser = useMutation({
    mutationFn: (id: string) => serviceRegistry.administrationUsers.delete(id),
    onSuccess: invalidate,
  })
  return { save, deactivate, delete: deleteUser }
}

export function useAdministrationSettings() {
  return useQuery({
    queryKey: queryKeys.administrationSettings(),
    queryFn: () => serviceRegistry.administrationSettings.get(),
  })
}

export function useAdministrationSettingsActions() {
  const queryClient = useQueryClient()
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: queryKeys.administrationSettings() })

  const saveSection = useMutation({
    mutationFn: (input: AdministrationSectionInput) =>
      serviceRegistry.administrationSettings.saveSection(
        input.key,
        input.value as never,
      ),
    onSuccess: invalidate,
  })
  const configureIntegration = useMutation({
    mutationFn: ({ id, workspace }: { id: string; workspace: string }) =>
      serviceRegistry.administrationSettings.configureIntegration(id, workspace),
    onSuccess: invalidate,
  })
  const setIntegrationStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: ManagedIntegration["status"] }) =>
      serviceRegistry.administrationSettings.setIntegrationStatus(id, status),
    onSuccess: invalidate,
  })
  const testIntegration = useMutation({
    mutationFn: (id: string) => serviceRegistry.administrationSettings.testIntegration(id),
    onSuccess: invalidate,
  })
  const setChannelIgnored = useMutation({
    mutationFn: ({ id, ignored }: { id: string; ignored: boolean }) =>
      serviceRegistry.administrationSettings.setChannelIgnored(id, ignored),
    onSuccess: invalidate,
  })
  const toggleNotification = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      serviceRegistry.administrationSettings.toggleNotification(id, enabled),
    onSuccess: invalidate,
  })

  return {
    saveSection,
    configureIntegration,
    setIntegrationStatus,
    testIntegration,
    setChannelIgnored,
    toggleNotification,
  }
}

export function useAnalytics(filters: AnalyticsFilters) {
  return useQuery({
    queryKey: queryKeys.analytics(filters),
    queryFn: () => serviceRegistry.analytics.calculate(filters),
    placeholderData: (previous) => previous,
    refetchInterval: 5_000,
    refetchOnWindowFocus: true,
  })
}
