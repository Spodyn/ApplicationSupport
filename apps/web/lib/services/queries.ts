"use client"

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useRealtimeCase } from "@/lib/realtime/realtime-context"
import { serviceRegistry } from "./registry"
import type {
  InboxAskInput,
  InboxIgnoreInput,
  InboxListQuery,
  InboxResolveInput,
  InboxSendInput,
} from "./inbox"
import type {
  AdministrationSettings,
  AdministrationUserInput,
  ManagedIntegration,
  IntegrationConfigurationInput,
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
  inboxCases: (query?: InboxListQuery) => query
    ? ["support-inbox", "cases", query] as const
    : ["support-inbox", "cases"] as const,
  inboxCase: (caseId: string) => ["support-inbox", "case", caseId] as const,
  inboxMessages: (caseId: string) => ["support-inbox", "messages", caseId] as const,
  inboxActivity: (caseId: string) => ["support-inbox", "activity", caseId] as const,
  inboxAssignees: (caseId: string) => ["support-inbox", "assignees", caseId] as const,
  inboxFilterOptions: () => ["support-inbox", "filter-options"] as const,
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

export function useInboxCases(query?: InboxListQuery) {
  return useInfiniteQuery({
    queryKey: queryKeys.inboxCases(query),
    queryFn: ({ pageParam }) => serviceRegistry.inbox.list(pageParam, query),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor,
  })
}

export function useInboxFilterOptions() {
  return useQuery({
    queryKey: queryKeys.inboxFilterOptions(),
    queryFn: () => serviceRegistry.inbox.getFilterOptions(),
    staleTime: 60_000,
  })
}

export function useInboxActivity(caseId?: string) {
  return useInfiniteQuery({
    queryKey: queryKeys.inboxActivity(caseId ?? ""),
    queryFn: ({ pageParam }) => serviceRegistry.inbox.getActivity(caseId ?? "", pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor,
    enabled: Boolean(caseId),
  })
}

export function useInboxAssignees(caseId?: string, enabled = false) {
  return useQuery({
    queryKey: queryKeys.inboxAssignees(caseId ?? ""),
    queryFn: () => serviceRegistry.inbox.listAssignees(caseId ?? ""),
    enabled: Boolean(caseId) && enabled,
  })
}

export function useInboxCase(caseId?: string) {
  return useQuery({
    queryKey: queryKeys.inboxCase(caseId ?? ""),
    queryFn: () => serviceRegistry.inbox.getCase(caseId ?? ""),
    enabled: Boolean(caseId),
  })
}

export function useInboxMessages(caseId?: string) {
  useRealtimeCase(caseId)
  return useInfiniteQuery({
    queryKey: queryKeys.inboxMessages(caseId ?? ""),
    queryFn: ({ pageParam }) => serviceRegistry.inbox.getMessages(caseId ?? "", pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor,
    enabled: Boolean(caseId),
    refetchInterval: (query) => query.state.data?.pages[0]?.items.some(
      (message) => message.deliveryStatus === "queued" || message.deliveryStatus === "sending",
    ) ? 2_000 : false,
  })
}

export function useMarkInboxCaseRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ caseId, messageId }: { caseId: string; messageId: string }) =>
      serviceRegistry.inbox.markRead(caseId, messageId),
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
      queryClient.invalidateQueries({ queryKey: queryKeys.inboxCase(caseId ?? "") }),
      queryClient.invalidateQueries({
        queryKey: queryKeys.inboxMessages(caseId ?? ""),
      }),
      queryClient.invalidateQueries({ queryKey: queryKeys.inboxActivity(caseId ?? "") }),
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
  const cancelSnooze = useMutation({
    mutationFn: () => serviceRegistry.inbox.cancelSnooze(requireCaseId()),
    onSuccess: invalidateConversation,
  })
  const assign = useMutation({
    mutationFn: ({ userId, reassign }: { userId: string; reassign: boolean }) =>
      serviceRegistry.inbox.assign(requireCaseId(), userId, reassign),
    onSuccess: invalidateConversation,
  })
  const unassign = useMutation({
    mutationFn: () => serviceRegistry.inbox.unassign(requireCaseId()),
    onSuccess: invalidateConversation,
  })
  const forceResolve = useMutation({
    mutationFn: (input: InboxResolveInput) => serviceRegistry.inbox.forceResolve(requireCaseId(), input),
    onSuccess: invalidateConversation,
  })
  const sendMessage = useMutation({
    mutationFn: (input: InboxSendInput) =>
      serviceRegistry.inbox.sendMessage(requireCaseId(), input),
    onSuccess: invalidateConversation,
  })
  const retryMessage = useMutation({
    mutationFn: (messageId: string) => serviceRegistry.inbox.retryMessage(messageId),
    onSuccess: invalidateConversation,
  })

  return { claim, ignore, askCustomer, resolve, snooze, cancelSnooze, assign, unassign, forceResolve, sendMessage, retryMessage }
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
    mutationFn: (input: IntegrationConfigurationInput) =>
      serviceRegistry.administrationSettings.configureIntegration(input),
    onSuccess: invalidate,
  })
  const setIntegrationStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: ManagedIntegration["status"] }) =>
      serviceRegistry.administrationSettings.setIntegrationStatus(id, status),
    onSuccess: invalidate,
  })
  const testIntegration = useMutation({
    mutationFn: (id: string) => serviceRegistry.administrationSettings.testIntegration(id),
    onSettled: invalidate,
  })
  const discoverChannels = useMutation({
    mutationFn: (id: string) => serviceRegistry.administrationSettings.discoverChannels(id),
    onSuccess: invalidate,
  })
  const configureChannel = useMutation({
    mutationFn: ({ id, customerId, ignored }: { id: string; customerId?: string; ignored: boolean }) =>
      serviceRegistry.administrationSettings.configureChannel(id, customerId, ignored),
    onSuccess: invalidate,
  })
  const saveCustomer = useMutation({
    mutationFn: ({ id, name, externalRef }: { id?: string; name: string; externalRef?: string }) =>
      serviceRegistry.administrationSettings.saveCustomer({ name, externalRef }, id),
    onSuccess: invalidate,
  })
  const deactivateCustomer = useMutation({
    mutationFn: (id: string) => serviceRegistry.administrationSettings.deactivateCustomer(id),
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
    discoverChannels,
    configureChannel,
    saveCustomer,
    deactivateCustomer,
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
