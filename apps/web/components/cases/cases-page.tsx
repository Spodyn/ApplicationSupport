"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import {
  AlarmClock,
  ArrowLeft,
  Check,
  ChevronDown,
  Clock3,
  Paperclip,
  RotateCcw,
  Search,
  SlidersHorizontal,
  ThumbsDown,
  UserRound,
} from "lucide-react"
import type { InboxCase, InboxMessage, InboxStatus } from "@/lib/domain/inbox"
import { inboxStatusLabels } from "@/lib/domain/inbox"
import { deliveryStatusLabels } from "@/lib/domain/labels"
import type { InboxListQuery } from "@/lib/services/inbox"
import { useCurrentUser, useInboxActivity, useInboxAssignees, useInboxCase, useInboxCases, useInboxFilterOptions, useInboxMessages, useInboxWorkflow, useMarkAllResolvedRead, useMarkInboxCaseRead } from "@/lib/services/queries"
import { cn } from "@/lib/utils"
import { SafeExternalMessage } from "./safe-external-message"

type QuickFilter = "all" | "sla" | "mine"
type AdvancedFilter = "mine" | "unassigned" | "sla" | "unread" | "snoozed" | "active" | "terminal"

type CasePresentation = {
  id: string
  reference: string
  initials: string
  company: string
  subject: string
  time: string
  platform: "Slack" | "Teams" | "Telegram"
  source: string
  status: string
  statusTone: "red" | "blue" | "purple" | "green"
  sla: string
  slaTone: "red" | "amber" | "green"
  unread: boolean
  breached: boolean
  mine: boolean
  unassigned: boolean
}

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0].toLocaleUpperCase("pl")).join("") || "?"
}

function timeLabel(value: string): string {
  return new Intl.DateTimeFormat("pl-PL", { dateStyle: "short", timeStyle: "short" }).format(new Date(value))
}

function toPresentation(record: InboxCase, currentUserId?: string): CasePresentation {
  const statusTone = record.status === "new" ? "green" : record.status === "verification" ? "purple" : record.status === "waiting_for_customer" ? "blue" : "red"
  const slaTone = record.sla.state === "breached" ? "red" : record.sla.state === "at_risk" ? "amber" : "green"
  const dueAt = record.sla.dueAt
  return {
    id: record.id,
    reference: record.reference,
    initials: initials(record.customer.name),
    company: record.customer.name,
    subject: record.subject,
    time: timeLabel(record.updatedAt),
    platform: record.platform === "slack" ? "Slack" : record.platform === "teams" ? "Teams" : "Telegram",
    source: record.sourceChannel,
    status: inboxStatusLabels[record.status],
    statusTone,
    sla: dueAt ? `SLA ${timeLabel(dueAt)}` : "SLA —",
    slaTone,
    unread: record.unreadForCurrentUser,
    breached: record.sla.state === "breached",
    mine: Boolean(currentUserId && record.owner?.id === currentUserId),
    unassigned: !record.owner,
  }
}

export function CasesPage({
  onlyMine = false,
  initialCaseId,
}: {
  onlyMine?: boolean
  initialCaseId?: string
}) {
  const [selectedCaseId, setSelectedCaseId] = useState<string | undefined>(initialCaseId)
  const [mobileConversationOpen, setMobileConversationOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [quickFilter, setQuickFilter] = useState<QuickFilter>(onlyMine ? "mine" : "all")
  const [filterMenuOpen, setFilterMenuOpen] = useState(false)
  const [advancedFilters, setAdvancedFilters] = useState<AdvancedFilter[]>([])
  const [serverSearch, setServerSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState<InboxStatus | "">("")
  const [customerFilter, setCustomerFilter] = useState("")
  const [channelFilter, setChannelFilter] = useState("")
  const [providerFilter, setProviderFilter] = useState<"slack" | "teams" | "telegram" | "">("")
  const [ownerFilter, setOwnerFilter] = useState("")
  const filterMenuRef = useRef<HTMLDivElement>(null)
  const readAttempt = useRef<string | null>(null)
  const listQuery = useMemo<InboxListQuery>(() => ({
    view: advancedFilters.includes("snoozed") ? "snoozed" : "active",
    mine: quickFilter === "mine" || advancedFilters.includes("mine"),
    unassigned: advancedFilters.includes("unassigned"),
    unread: advancedFilters.includes("unread"),
    slaAttention: quickFilter === "sla" || advancedFilters.includes("sla"),
    statusScope: advancedFilters.includes("terminal") ? "terminal" : advancedFilters.includes("active") ? "active" : "all",
    status: statusFilter || undefined,
    customerId: customerFilter || undefined,
    channelId: channelFilter || undefined,
    provider: providerFilter || undefined,
    ownerId: ownerFilter || undefined,
    search: serverSearch || undefined,
  }), [advancedFilters, channelFilter, customerFilter, ownerFilter, providerFilter, quickFilter, serverSearch, statusFilter])
  const casesQuery = useInboxCases(listQuery)
  const filterOptions = useInboxFilterOptions()
  const currentUserQuery = useCurrentUser()
  const markRead = useMarkInboxCaseRead()
  const markResolvedRead = useMarkAllResolvedRead()

  const records = useMemo(() => casesQuery.data?.pages.flatMap((page) => page.items) ?? [], [casesQuery.data])
  if (selectedCaseId === undefined && records[0]?.id) {
    setSelectedCaseId(records[0].id)
  }
  const selectedId = selectedCaseId ?? records[0]?.id
  const detailQuery = useInboxCase(selectedId)
  const messagesQuery = useInboxMessages(selectedId)
  const workflow = useInboxWorkflow(selectedId)
  const presentations = useMemo(() => records.map((record) => toPresentation(record, currentUserQuery.data?.id)), [records, currentUserQuery.data?.id])
  const selectedPresentation = presentations.find((item) => item.id === selectedId)
    ?? (detailQuery.data ? toPresentation(detailQuery.data, currentUserQuery.data?.id) : undefined)
  const messages = useMemo(() => {
    const seen = new Set<string>()
    return (messagesQuery.data?.pages.flatMap((page) => page.items) ?? [])
      .filter((message) => { if (seen.has(message.id)) return false; seen.add(message.id); return true }).reverse()
  }, [messagesQuery.data])
  const latestMessageId = messagesQuery.data?.pages[0]?.items[0]?.id

  const visibleCases = presentations

  useEffect(() => {
    const timer = window.setTimeout(() => setServerSearch(search.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [search])

  useEffect(() => {
    const handlePointerDown = (event: PointerEvent) => {
      if (!filterMenuRef.current?.contains(event.target as Node)) setFilterMenuOpen(false)
    }
    document.addEventListener("pointerdown", handlePointerDown)
    return () => document.removeEventListener("pointerdown", handlePointerDown)
  }, [])

  useEffect(() => {
    const key = selectedId && latestMessageId ? `${selectedId}:${latestMessageId}` : null
    if (selectedId && latestMessageId && key !== readAttempt.current) {
      readAttempt.current = key
      markRead.mutate({ caseId: selectedId, messageId: latestMessageId }, {
        onError: () => { readAttempt.current = null },
      })
    }
  }, [selectedId, latestMessageId, markRead])

  const selectCase = (item: CasePresentation) => {
    setSelectedCaseId(item.id)
    setMobileConversationOpen(true)
  }

  const toggleAdvancedFilter = (filter: AdvancedFilter) => {
    setAdvancedFilters((current) => {
      if (current.includes(filter)) return current.filter((item) => item !== filter)
      const withoutOppositeScope = filter === "active"
        ? current.filter((item) => item !== "terminal")
        : filter === "terminal"
          ? current.filter((item) => item !== "active")
          : current
      return [...withoutOppositeScope, filter]
    })
  }

  return (
    <main className="cases-workspace grid min-h-0 flex-1 grid-cols-1 overflow-hidden text-[#f4f4f5] lg:grid-cols-[434px_minmax(0,1fr)]">
      <section
        className={cn(
          "min-h-0 min-w-0 flex-col border-r border-white/[0.09] bg-[#0b1422]",
          mobileConversationOpen ? "hidden lg:flex" : "flex",
        )}
        aria-label="Lista czatów"
      >
        <div className="shrink-0 px-[34px] pb-[14px] pt-[23px]">
          <div className="flex h-8 items-center gap-2.5">
            <h1 className="text-[21px] font-bold tracking-[-0.02em]">Czaty</h1>
            <span className="rounded-full bg-[#151f2e] px-2 py-0.5 text-[12px] font-semibold text-[#d8dce4]">{records.length}{casesQuery.hasNextPage ? "+" : ""}</span>
          </div>

          <div className="mt-[17px] flex gap-3">
            <label className="flex h-[42px] min-w-0 flex-1 items-center gap-2.5 rounded-[9px] border border-white/[0.07] bg-[#111b29] px-3 text-[#8f9aad] shadow-[inset_0_1px_0_rgba(255,255,255,0.015)] focus-within:border-violet-500/50">
              <Search className="size-[18px] shrink-0" strokeWidth={1.8} />
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                className="min-w-0 flex-1 bg-transparent text-[13px] text-[#e8eaf0] outline-none placeholder:text-[#8f9aad]"
                placeholder="Szukaj rozmów..."
                aria-label="Szukaj rozmów"
              />
            </label>
            <div className="relative" ref={filterMenuRef}>
              <button
                type="button"
                onClick={() => setFilterMenuOpen((open) => !open)}
                className={cn(
                  "grid size-[42px] place-items-center rounded-[9px] border bg-[#111b29] text-[#99a5b7] transition hover:text-white",
                  filterMenuOpen || advancedFilters.length ? "border-violet-500/50 text-violet-300" : "border-white/[0.07]",
                )}
                aria-label="Filtry"
                aria-expanded={filterMenuOpen}
              >
                <SlidersHorizontal className="size-[18px]" strokeWidth={1.6} />
              </button>
              {filterMenuOpen && (
                <div className="absolute right-0 top-12 z-30 w-72 rounded-xl border border-white/10 bg-[#111b29] p-2 shadow-2xl">
                  {([
                    ["mine", "Moje"],
                    ["unassigned", "Nieprzypisane"],
                    ["sla", "SLA"],
                    ["unread", "Nieodczytane"],
                    ["snoozed", "Odłożone"],
                    ["active", "Aktywne"],
                    ["terminal", "Zakończone"],
                  ] as const).map(([value, label]) => {
                    const active = advancedFilters.includes(value)
                    return (
                      <button
                        key={value}
                        type="button"
                        onClick={() => toggleAdvancedFilter(value)}
                        className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] text-[#d8dde6] hover:bg-white/[0.05]"
                      >
                        <span className={cn("grid size-4 place-items-center rounded border", active ? "border-violet-500 bg-violet-600" : "border-[#536074]")}>{active && <Check className="size-3" />}</span>
                        <span className={value === "unread" ? "font-semibold" : undefined}>{label}</span>
                      </button>
                    )
                  })}
                  <div className="mt-2 grid gap-2 border-t border-white/10 pt-2">
                    <FilterSelect label="Status" value={statusFilter} onChange={(value) => setStatusFilter(value as InboxStatus | "")} options={Object.entries(inboxStatusLabels).map(([value, label]) => ({ value, label }))} />
                    <FilterSelect label="Klient" value={customerFilter} onChange={setCustomerFilter} options={(filterOptions.data?.customers ?? []).map((item) => ({ value: item.id, label: item.name }))} />
                    <FilterSelect label="Kanał" value={channelFilter} onChange={setChannelFilter} options={(filterOptions.data?.channels ?? []).map((item) => ({ value: item.id, label: item.name }))} />
                    <FilterSelect label="Dostawca" value={providerFilter} onChange={(value) => setProviderFilter(value as typeof providerFilter)} options={[{ value: "slack", label: "Slack" }, { value: "teams", label: "Teams" }, { value: "telegram", label: "Telegram" }]} />
                    <FilterSelect label="Właściciel" value={ownerFilter} onChange={setOwnerFilter} options={(filterOptions.data?.owners ?? []).map((item) => ({ value: item.id, label: item.name }))} />
                  </div>
                  <button type="button" disabled={markResolvedRead.isPending} onClick={() => markResolvedRead.mutate()} className="mt-1 w-full border-t border-white/10 px-2.5 py-2 text-left text-[12px] text-violet-300 disabled:opacity-50">Oznacz rozwiązane jako przeczytane</button>
                </div>
              )}
            </div>
          </div>

          <div className="mt-[13px] flex gap-2.5" aria-label="Szybkie filtry">
            <QuickFilterButton active={quickFilter === "all"} onClick={() => setQuickFilter("all")}>Wszystkie</QuickFilterButton>
            <QuickFilterButton active={quickFilter === "sla"} onClick={() => setQuickFilter("sla")}>
              SLA <ChevronDown className="size-3.5" />
            </QuickFilterButton>
            <QuickFilterButton active={quickFilter === "mine"} onClick={() => setQuickFilter("mine")}>Moje</QuickFilterButton>
          </div>
        </div>

        <div className="cases-scrollbar min-h-0 flex-1 overflow-y-auto pb-3 pl-[13px] pr-[2px]" role="listbox" aria-label="Rozmowy">
          {casesQuery.isLoading ? (
            <CaseListSkeleton />
          ) : casesQuery.isError ? (
            <div className="mt-10 px-5 text-center text-sm text-[#9aa5b6]">Nie udało się wczytać rozmów.</div>
          ) : visibleCases.length ? (
            visibleCases.map((item) => (
              <CaseListItem
                key={item.id}
                item={item}
                selected={item.id === selectedId}
                unread={item.unread}
                onSelect={() => selectCase(item)}
              />
            ))
          ) : (
            <div className="mt-10 px-5 text-center text-sm text-[#9aa5b6]">Brak rozmów pasujących do filtrów.</div>
          )}
          {casesQuery.hasNextPage && <button type="button" onClick={() => casesQuery.fetchNextPage()} disabled={casesQuery.isFetchingNextPage} className="mx-5 mb-4 text-sm text-violet-300">Wczytaj więcej</button>}
        </div>
      </section>

      {selectedPresentation ? <ConversationPanel
        key={selectedId}
        item={selectedPresentation}
        record={detailQuery.data}
        detailError={detailQuery.isError}
        messages={messages}
        messagesLoading={messagesQuery.isLoading}
        messagesError={messagesQuery.isError}
        hasOlderMessages={Boolean(messagesQuery.hasNextPage)}
        loadOlderMessages={() => messagesQuery.fetchNextPage()}
        workflow={workflow}
        onBack={() => setMobileConversationOpen(false)}
        className={mobileConversationOpen ? "flex" : "hidden lg:flex"}
      /> : <section className="hidden min-h-0 items-center justify-center bg-[#08111f] text-sm text-[#9aa5b6] lg:flex">{casesQuery.isError ? "Nie udało się wczytać rozmowy." : "Wybierz rozmowę"}</section>}
    </main>
  )
}

function QuickFilterButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex h-8 items-center gap-1.5 rounded-[7px] border px-3 text-[12px] font-medium transition",
        active
          ? "border-violet-600 bg-[#5724d6] text-white shadow-[0_2px_12px_rgba(91,33,232,0.23)]"
          : "border-white/[0.09] bg-[#0d1624] text-[#c9ced8] hover:border-white/20 hover:text-white",
      )}
    >
      {children}
    </button>
  )
}

function CaseListSkeleton() {
  return (
    <div className="space-y-1.5">
      {Array.from({ length: 6 }, (_, index) => (
        <div key={index} className="h-[142px] animate-pulse rounded-[9px] border border-white/[0.05] bg-white/[0.025]" />
      ))}
    </div>
  )
}

function CaseListItem({
  item,
  selected,
  unread,
  onSelect,
}: {
  item: CasePresentation
  selected: boolean
  unread: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      onClick={onSelect}
      className={cn(
        "group relative mb-[5px] flex min-h-[139px] w-full overflow-hidden rounded-[9px] border px-[17px] py-[13px] text-left transition-colors",
        item.breached
          ? "border-red-500/45 bg-[linear-gradient(105deg,rgba(89,24,32,0.52),rgba(54,20,31,0.48))] hover:border-red-400/60"
          : selected
            ? "border-violet-500/45 bg-[linear-gradient(105deg,rgba(48,30,88,0.7),rgba(25,25,54,0.72))] shadow-[inset_0_0_22px_rgba(91,33,232,0.08)]"
            : unread
              ? "border-violet-500/25 bg-[linear-gradient(105deg,rgba(29,29,57,0.92),rgba(24,26,45,0.94))] hover:border-violet-500/40"
              : "border-white/[0.075] bg-[#0f1927] hover:border-white/[0.15] hover:bg-[#121d2c]",
      )}
    >
      <span className={cn("absolute inset-y-0 left-0 w-1", item.breached ? "bg-red-500" : item.slaTone === "amber" ? "bg-amber-500" : item.slaTone === "green" ? "bg-emerald-500" : "bg-violet-500")} />
      <span className={cn("mr-[16px] grid size-[38px] shrink-0 place-items-center rounded-[7px] text-[14px] font-semibold", avatarTone(item.initials))}>{item.initials}</span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className={cn("truncate text-[15px] leading-5", unread ? "font-bold text-white" : "font-normal text-[#eff0f4]")}>{item.company}</span>
          <span className={cn("ml-auto shrink-0 text-[11px] tabular-nums", unread ? "font-semibold text-[#d4d9e3]" : "text-[#9ba5b5]")}>{item.time}</span>
          {unread && <span className="size-2.5 shrink-0 rounded-full bg-[#7650ff] shadow-[0_0_8px_rgba(118,80,255,0.6)]" aria-label="Nieodczytane" />}
        </span>
        <span className={cn("mt-[2px] block max-w-[280px] text-[13px] leading-[18px] text-[#edf0f4]", unread ? "font-bold" : "font-normal")}>{item.subject}</span>
        <span className="mt-[4px] flex min-w-0 items-center gap-2 text-[11px] text-[#9ca6b6]">
          <span>{item.platform}</span><span className="size-1 rounded-full bg-[#6c7585]" /><span className="truncate">{item.source}</span>
        </span>
        <span className="mt-[10px] flex items-center justify-between gap-2">
          <StatusBadge tone={item.statusTone}>{item.status}</StatusBadge>
          <SlaBadge tone={item.slaTone}>{item.sla}</SlaBadge>
        </span>
      </span>
    </button>
  )
}

function avatarTone(initials: string) {
  if (initials === "NR") return "bg-[#522033] text-[#ffe9f1]"
  if (initials === "EC") return "bg-[#35253e] text-[#f3e8ff]"
  if (initials === "OL" || initials === "AC") return "bg-[#1e314b] text-[#e4edfb]"
  return "bg-[#30205c] text-[#efe8ff]"
}

function StatusBadge({ tone, children }: { tone: CasePresentation["statusTone"]; children: React.ReactNode }) {
  const colors = {
    red: "bg-red-500/[0.10] text-red-400",
    blue: "bg-sky-500/[0.12] text-sky-400",
    purple: "bg-violet-500/[0.13] text-violet-300",
    green: "bg-emerald-500/[0.12] text-emerald-400",
  }
  return <span className={cn("rounded-[6px] px-2.5 py-[5px] text-[11px] font-medium leading-none", colors[tone])}>{children}</span>
}

function SlaBadge({ tone, children }: { tone: CasePresentation["slaTone"]; children: React.ReactNode }) {
  const colors = {
    red: "bg-red-500/[0.10] text-red-400",
    amber: "bg-amber-500/[0.10] text-amber-400",
    green: "bg-emerald-500/[0.09] text-emerald-400",
  }
  return <span className={cn("rounded-[6px] px-2.5 py-[5px] text-[11px] font-medium leading-none", colors[tone])}>{children}</span>
}

function ConversationPanel({
  item, record, detailError, messages, messagesLoading, messagesError, hasOlderMessages, loadOlderMessages, workflow, onBack, className,
}: {
  item: CasePresentation
  record?: InboxCase
  detailError: boolean
  messages: InboxMessage[]
  messagesLoading: boolean
  messagesError: boolean
  hasOlderMessages: boolean
  loadOlderMessages: () => void
  workflow: ReturnType<typeof useInboxWorkflow>
  onBack: () => void
  className: string
}) {
  const [draft, setDraft] = useState("")
  const [files, setFiles] = useState<File[]>([])
  const [sendError, setSendError] = useState("")
  const [actionDialog, setActionDialog] = useState<"ask" | "ignore" | "resolve" | "snooze" | "admin" | null>(null)
  const [actionText, setActionText] = useState("")
  const [resolutionCategory, setResolutionCategory] = useState("")
  const [waitingMinutes, setWaitingMinutes] = useState(24 * 60)
  const [snoozeUntil, setSnoozeUntil] = useState(() => localDateTime(new Date(Date.now() + 60 * 60_000)))
  const [assigneeId, setAssigneeId] = useState("")
  const [actionError, setActionError] = useState("")
  const idempotencyKey = useRef<string | null>(null)
  const workflowActionKey = useRef<string | null>(null)
  const canClaim = Boolean(record?.availableActions?.includes("CLAIM"))
  const canReply = Boolean(record?.availableActions?.includes("REPLY"))
  const canAsk = Boolean(record?.availableActions?.includes("ASK_CUSTOMER"))
  const canIgnore = Boolean(record?.availableActions?.includes("IGNORE"))
  const canResolve = Boolean(record?.availableActions?.includes("RESOLVE"))
  const canSnooze = Boolean(record?.availableActions?.includes("SNOOZE"))
  const canAdmin = Boolean(record?.availableActions?.some((action) => ["ASSIGN", "REASSIGN", "UNASSIGN", "FORCE_RESOLVE"].includes(action)))
  const assignees = useInboxAssignees(record?.id, actionDialog === "admin")
  const activity = useInboxActivity(record?.id)
  const activityItems = activity.data?.pages.flatMap((page) => page.items) ?? []
  const actionPending = workflow.askCustomer.isPending
    || workflow.ignore.isPending
    || workflow.resolve.isPending
    || workflow.snooze.isPending
    || workflow.assign.isPending
    || workflow.unassign.isPending
    || workflow.forceResolve.isPending

  const send = async () => {
    if (!canReply || !draft.trim() || workflow.sendMessage.isPending) return
    idempotencyKey.current ??= crypto.randomUUID()
    setSendError("")
    try {
      await workflow.sendMessage.mutateAsync({ body: draft, attachments: files, idempotencyKey: idempotencyKey.current })
      setDraft("")
      setFiles([])
      idempotencyKey.current = null
    } catch (error) {
      setSendError(error instanceof Error ? error.message : "Nie udało się wysłać odpowiedzi.")
    }
  }

  const performAction = async () => {
    if (actionPending) return
    setActionError("")
    try {
      if (actionDialog === "ask") {
        workflowActionKey.current ??= crypto.randomUUID()
        await workflow.askCustomer.mutateAsync({ message: actionText, waitingMinutes, idempotencyKey: workflowActionKey.current })
      } else if (actionDialog === "ignore") {
        await workflow.ignore.mutateAsync({ reason: actionText.trim() || undefined })
      } else if (actionDialog === "resolve") {
        workflowActionKey.current ??= crypto.randomUUID()
        await workflow.resolve.mutateAsync({ category: resolutionCategory || undefined, idempotencyKey: workflowActionKey.current })
      } else if (actionDialog === "snooze") {
        await workflow.snooze.mutateAsync(new Date(snoozeUntil).toISOString())
      }
      setActionDialog(null)
      setActionText("")
      workflowActionKey.current = null
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Nie udało się wykonać akcji.")
    }
  }

  const performAdminAction = async (action: "assign" | "reassign" | "unassign" | "forceResolve") => {
    if (actionPending) return
    setActionError("")
    try {
      if (action === "assign" || action === "reassign") {
        if (!assigneeId) throw new Error("Wybierz agenta.")
        await workflow.assign.mutateAsync({ userId: assigneeId, reassign: action === "reassign" })
      } else if (action === "unassign") {
        await workflow.unassign.mutateAsync()
      } else {
        workflowActionKey.current ??= crypto.randomUUID()
        await workflow.forceResolve.mutateAsync({ category: resolutionCategory || undefined, idempotencyKey: workflowActionKey.current })
      }
      setActionDialog(null)
      workflowActionKey.current = null
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Nie udało się wykonać akcji administratora.")
    }
  }

  return (
    <section className={cn(className, "min-h-0 min-w-0 flex-col bg-[#08111f]")} aria-label={`Rozmowa ${item.company}`}>
      <header className="h-[148px] shrink-0 border-b border-white/[0.08] bg-[#08111f] px-[23px] py-[19px]">
        <div className="flex min-w-0 items-start gap-3">
          <button type="button" onClick={onBack} className="mt-1 grid size-8 shrink-0 place-items-center rounded-lg text-[#9ba6b8] hover:bg-white/5 lg:hidden" aria-label="Wróć do listy"><ArrowLeft className="size-5" /></button>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2"><h2 className="truncate text-[22px] font-bold leading-7 tracking-[-0.02em]">{item.source}</h2></div>
            <div className="mt-[3px] flex items-center gap-2 text-[13px] text-[#d7dbe3]"><span>{item.company}</span><span className="size-1 rounded-full bg-[#697587]" /><span className="font-medium text-[#e0e3e9]">{item.platform === "Slack" && <SlackMark />} {item.platform} · {item.reference}</span></div>
          </div>
          <div className="hidden shrink-0 items-center gap-3 xl:flex">
            <button type="button" onClick={() => workflow.claim.mutate()} disabled={!canClaim || workflow.claim.isPending} className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#d8dce4] disabled:cursor-not-allowed disabled:opacity-50"><UserRound className="size-[17px]" />{workflow.claim.isPending ? "Przejmowanie…" : "Przejmij"}</button>
            <button type="button" onClick={() => record?.snoozedForCurrentUserUntil ? workflow.cancelSnooze.mutate() : setActionDialog("snooze")} disabled={!record?.snoozedForCurrentUserUntil && !canSnooze} className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#d8dce4] disabled:opacity-50"><AlarmClock className="size-[17px]" />{record?.snoozedForCurrentUserUntil ? "Anuluj odłożenie" : "Odłóż"}</button>
            <button type="button" onClick={() => setActionDialog("ignore")} disabled={!canIgnore} className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#d8dce4] disabled:opacity-50"><ThumbsDown className="size-[17px]" /> Ignoruj {record ? `${record.ignoreVotes.current}/2` : ""}</button>
            <button type="button" onClick={() => setActionDialog("ask")} disabled={!canAsk} className="flex h-[42px] items-center rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#d8dce4] disabled:opacity-50">Dopytaj</button>
            <button type="button" onClick={() => setActionDialog("resolve")} disabled={!canResolve} className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#d8dce4] disabled:opacity-50"><Check className="size-[17px]" /> Rozwiąż</button>
            {canAdmin && <button type="button" onClick={() => setActionDialog("admin")} className="flex h-[42px] items-center rounded-[9px] border border-violet-500/20 bg-violet-950/30 px-4 text-[12px] text-violet-200">Administruj</button>}
          </div>
        </div>
        <div className="mt-[15px] flex items-center gap-3 overflow-x-auto">
          <button type="button" onClick={() => workflow.claim.mutate()} disabled={!canClaim || workflow.claim.isPending} className="h-[45px] rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3 text-[12px] text-[#edf0f4] disabled:cursor-not-allowed disabled:opacity-50 xl:hidden">Przejmij</button>
          <button type="button" onClick={() => setActionDialog("ask")} disabled={!canAsk} className="h-[45px] rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3 text-[12px] disabled:opacity-50 xl:hidden">Dopytaj</button>
          <button type="button" onClick={() => setActionDialog("ignore")} disabled={!canIgnore} className="h-[45px] rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3 text-[12px] disabled:opacity-50 xl:hidden">Ignoruj</button>
          <button type="button" onClick={() => record?.snoozedForCurrentUserUntil ? workflow.cancelSnooze.mutate() : setActionDialog("snooze")} disabled={!record?.snoozedForCurrentUserUntil && !canSnooze} className="h-[45px] rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3 text-[12px] disabled:opacity-50 xl:hidden">Odłóż</button>
          <button type="button" onClick={() => setActionDialog("resolve")} disabled={!canResolve} className="h-[45px] rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3 text-[12px] disabled:opacity-50 xl:hidden">Rozwiąż</button>
          {canAdmin && <button type="button" onClick={() => setActionDialog("admin")} className="h-[45px] rounded-[9px] border border-violet-500/20 bg-violet-950/30 px-3 text-[12px] text-violet-200 xl:hidden">Admin</button>}
          <div className="flex h-[45px] items-center gap-2 rounded-[9px] border border-violet-500/[0.12] bg-violet-950/35 px-3.5 text-[12px] font-medium text-violet-300">{item.status}</div>
          <div className="flex h-[45px] items-center gap-2.5 rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3.5 text-[12px] text-[#edf0f4]"><Avatar initials={initials(record?.owner?.fullName ?? "?")} size="sm" />{record?.owner?.fullName ?? "Nieprzypisane"}</div>
          <div className="flex h-[45px] items-center gap-2.5 rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3.5 text-[12px] font-semibold text-[#aeb8c8]"><Clock3 className="size-[17px]" />{item.sla}</div>
          {record?.relatedCase?.id && <a href={`/cases?caseId=${encodeURIComponent(record.relatedCase.id)}`} className="text-xs text-violet-300">Powiązany: {record.relatedCase.reference}</a>}
        </div>
        {detailError && <p role="alert" className="text-xs text-red-400">Nie udało się wczytać szczegółów case’u.</p>}
        {workflow.claim.isError && <p role="alert" className="text-xs text-red-400">{workflow.claim.error.message}</p>}
        {(workflow.cancelSnooze.isError || workflow.retryMessage.isError) && <p role="alert" className="text-xs text-red-400">Nie udało się odświeżyć stanu sprawy.</p>}
      </header>

      <div className="cases-scrollbar min-h-0 flex-1 overflow-y-auto bg-[radial-gradient(circle_at_72%_34%,rgba(23,48,76,0.12),transparent_42%)] py-[22px] pl-[22px] pr-[12px]">
        {hasOlderMessages && <button type="button" onClick={loadOlderMessages} className="mb-5 text-sm text-violet-300">Wczytaj starsze wiadomości</button>}
        {messagesLoading ? <p className="text-sm text-[#9aa5b6]">Wczytywanie wiadomości…</p> : messagesError ? <p role="alert" className="text-sm text-red-400">Nie udało się wczytać wiadomości.</p> : messages.length === 0 ? <p className="text-sm text-[#9aa5b6]">Brak wiadomości.</p> : (
          <div className="flex flex-col gap-6">
            {messages.map((message) => message.kind === "support" ? (
              <div key={message.id} className="flex justify-end"><div className="max-w-[570px] rounded-[12px] bg-[linear-gradient(135deg,rgba(52,28,104,0.86),rgba(32,24,73,0.9))] px-4 py-3 text-sm text-[#ded9eb]"><div className="mb-2 text-xs text-[#cfc4e8]">{message.sender ?? "Wsparcie"} · {timeLabel(message.createdAt)}{message.edited ? " · edytowano" : ""}{message.deliveryStatus ? ` · ${deliveryStatusLabels[message.deliveryStatus]}` : ""}</div><p className={cn("whitespace-pre-wrap", message.deleted && "italic text-[#aeb8c8]")}><SafeExternalMessage content={message.body} /></p><MessageAttachments attachments={message.attachments} />{message.deliveryStatus === "failed" && <p className="mt-2 text-xs text-red-200">{message.deliveryErrorCode === "SLACK_IS_ARCHIVED" ? "Kanał Slack jest zarchiwizowany." : "Nie udało się dostarczyć wiadomości."}</p>}{message.retryAllowed && <button type="button" onClick={() => workflow.retryMessage.mutate(message.id)} disabled={workflow.retryMessage.isPending} className="mt-3 inline-flex items-center gap-1.5 rounded-md border border-red-300/20 px-2.5 py-1.5 text-xs text-red-200 disabled:opacity-50"><RotateCcw className="size-3.5" /> Ponów wysyłkę</button>}</div></div>
            ) : message.kind === "system" ? (
              <div key={message.id} className="text-center text-xs text-[#9ba6b6]">{timeLabel(message.createdAt)} · <SafeExternalMessage content={message.body} /></div>
            ) : (
              <div key={message.id} className="flex items-start gap-4"><Avatar initials={initials(message.sender ?? item.company)} /><div className="max-w-[570px]"><div className="text-xs text-[#aeb8c8]">{message.sender ?? item.company} · {timeLabel(message.createdAt)}{message.edited ? " · edytowano" : ""}</div><p className={cn("mt-2 whitespace-pre-wrap text-[14px] leading-6 text-[#edf0f4]", message.deleted && "italic text-[#aeb8c8]")}><SafeExternalMessage content={message.body} /></p><MessageAttachments attachments={message.attachments} /></div></div>
            ))}
          </div>
        )}
      </div>

      <div className="shrink-0 pb-[22px] pl-[18px] pr-[22px]">
        {activityItems.length > 0 && <details className="mb-2 rounded-lg border border-white/[0.07] bg-[#0d1624] px-3 py-2 text-xs text-[#aeb8c8]"><summary className="cursor-pointer text-[#d8dce4]">Historia aktywności ({activityItems.length})</summary><div className="mt-2 max-h-28 space-y-1 overflow-y-auto">{activityItems.map((event) => <div key={event.id} className="flex justify-between gap-3"><span>{event.label} · {event.author ?? "System"}</span><time>{timeLabel(event.createdAt)}</time></div>)}</div>{activity.hasNextPage && <button type="button" onClick={() => activity.fetchNextPage()} className="mt-2 text-violet-300">Wczytaj starsze</button>}</details>}
        <div className="rounded-[11px] border border-white/[0.085] bg-[linear-gradient(110deg,#0d1725,#0b1522)]">
          <textarea value={draft} onChange={(event) => { setDraft(event.target.value); idempotencyKey.current = null }} disabled={!canReply} placeholder={canReply ? "Napisz odpowiedź..." : "Przejmij case, aby odpowiedzieć"} aria-label="Treść odpowiedzi" className="block h-[80px] w-full resize-none bg-transparent px-[23px] pt-[17px] text-[13px] text-[#eef0f4] outline-none placeholder:text-[#8e99aa] disabled:cursor-not-allowed" />
          {files.length > 0 && <div className="flex flex-wrap gap-2 px-[19px] pb-2">{files.map((file, index) => <button key={`${file.name}-${file.size}-${index}`} type="button" onClick={() => { setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index)); idempotencyKey.current = null }} className="rounded-md bg-white/[0.06] px-2 py-1 text-xs text-[#cbd3df]" title="Usuń plik">{file.name}</button>)}</div>}
          <div className="flex h-[51px] items-center justify-between px-[19px]"><label className={cn("inline-flex cursor-pointer items-center gap-2 text-xs text-[#aeb8c8]", !canReply && "pointer-events-none opacity-50")}><Paperclip className="size-4" /> Dodaj pliki<input type="file" multiple disabled={!canReply} className="hidden" onChange={(event) => { const selected = Array.from(event.target.files ?? []); setFiles((current) => [...current, ...selected].slice(0, 10)); idempotencyKey.current = null; event.target.value = "" }} /></label><button type="button" onClick={send} disabled={!canReply || !draft.trim() || workflow.sendMessage.isPending} className="h-[45px] w-[117px] rounded-[9px] bg-[linear-gradient(135deg,#5b23e5,#4c17c9)] text-[12px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-50">{workflow.sendMessage.isPending ? "Wysyłanie…" : "Wyślij"}</button></div>
        </div>
        {sendError && <p role="alert" className="mt-2 text-sm text-red-400">{sendError}</p>}
      </div>
      {actionDialog && <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4" role="dialog" aria-modal="true"><div className="w-full max-w-md rounded-xl border border-white/10 bg-[#111b29] p-5 shadow-2xl"><h3 className="text-lg font-semibold text-white">{actionDialog === "ask" ? "Dopytaj klienta" : actionDialog === "ignore" ? "Głos ignorowania" : actionDialog === "resolve" ? "Rozwiąż sprawę" : actionDialog === "snooze" ? "Odłóż sprawę" : "Administracja sprawą"}</h3>
        {actionDialog === "ask" && <><textarea autoFocus value={actionText} onChange={(event) => setActionText(event.target.value)} className="mt-4 h-28 w-full rounded-lg border border-white/10 bg-[#08111f] p-3 text-sm" placeholder="Pytanie do klienta" /><label className="mt-3 block text-xs text-[#aeb8c8]">Czas oczekiwania<select value={waitingMinutes} onChange={(event) => setWaitingMinutes(Number(event.target.value))} className="mt-1 w-full rounded-lg border border-white/10 bg-[#08111f] p-2"><option value={60}>1 godzina</option><option value={24 * 60}>24 godziny</option><option value={3 * 24 * 60}>3 dni</option><option value={7 * 24 * 60}>7 dni</option></select></label></>}
        {actionDialog === "ignore" && <textarea autoFocus value={actionText} onChange={(event) => setActionText(event.target.value)} className="mt-4 h-24 w-full rounded-lg border border-white/10 bg-[#08111f] p-3 text-sm" placeholder="Powód (opcjonalnie)" />}
        {(actionDialog === "resolve" || actionDialog === "admin") && <label className="mt-4 block text-xs text-[#aeb8c8]">Kategoria rozwiązania<select value={resolutionCategory} onChange={(event) => setResolutionCategory(event.target.value)} className="mt-1 w-full rounded-lg border border-white/10 bg-[#08111f] p-2"><option value="">Bez kategorii</option><option value="SOLVED">Rozwiązane</option><option value="NO_ACTION_REQUIRED">Brak wymaganej akcji</option><option value="DUPLICATE">Duplikat</option><option value="OTHER">Inne</option></select></label>}
        {actionDialog === "snooze" && <><div className="mt-4 flex gap-2">{[["1h", 60], ["Jutro", 24 * 60], ["Tydzień", 7 * 24 * 60]].map(([label, minutes]) => <button key={String(label)} type="button" onClick={() => setSnoozeUntil(localDateTime(new Date(Date.now() + Number(minutes) * 60_000)))} className="rounded-md border border-white/10 px-3 py-1.5 text-xs">{label}</button>)}</div><input type="datetime-local" value={snoozeUntil} onChange={(event) => setSnoozeUntil(event.target.value)} className="mt-3 w-full rounded-lg border border-white/10 bg-[#08111f] p-2 text-sm" /></>}
        {actionDialog === "admin" && <label className="mt-4 block text-xs text-[#aeb8c8]">Agent<select value={assigneeId} onChange={(event) => setAssigneeId(event.target.value)} className="mt-1 w-full rounded-lg border border-white/10 bg-[#08111f] p-2"><option value="">Wybierz agenta</option>{assignees.data?.filter((agent) => agent.eligible).map((agent) => <option key={agent.id} value={agent.id}>{agent.displayName}{agent.ignoreWeight === 2 ? " · głos ignorowania ×2" : ""}</option>)}</select></label>}
        {actionError && <p role="alert" className="mt-3 text-sm text-red-400">{actionError}</p>}
        <div className="mt-5 flex flex-wrap justify-end gap-2"><button type="button" onClick={() => { setActionDialog(null); setActionError(""); workflowActionKey.current = null }} disabled={actionPending} className="rounded-lg border border-white/10 px-4 py-2 text-sm disabled:opacity-50">Anuluj</button>{actionDialog === "admin" ? <>{record?.availableActions?.includes("ASSIGN") && <button type="button" onClick={() => void performAdminAction("assign")} disabled={actionPending || !assigneeId} className="rounded-lg bg-violet-700 px-4 py-2 text-sm disabled:opacity-50">Przypisz</button>}{record?.availableActions?.includes("REASSIGN") && <button type="button" onClick={() => void performAdminAction("reassign")} disabled={actionPending || !assigneeId} className="rounded-lg bg-violet-700 px-4 py-2 text-sm disabled:opacity-50">Przypisz ponownie</button>}{record?.availableActions?.includes("UNASSIGN") && <button type="button" onClick={() => void performAdminAction("unassign")} disabled={actionPending} className="rounded-lg border border-amber-400/30 px-4 py-2 text-sm text-amber-200 disabled:opacity-50">Cofnij przypisanie</button>}{record?.availableActions?.includes("FORCE_RESOLVE") && <button type="button" onClick={() => void performAdminAction("forceResolve")} disabled={actionPending} className="rounded-lg bg-red-700 px-4 py-2 text-sm disabled:opacity-50">Wymuś rozwiązanie</button>}</> : <button type="button" onClick={() => void performAction()} disabled={actionPending || (actionDialog === "ask" && !actionText.trim())} className="rounded-lg bg-violet-700 px-4 py-2 text-sm disabled:opacity-50">{actionPending ? "Zapisywanie…" : "Zatwierdź"}</button>}</div>
      </div></div>}
    </section>
  )
}

function FilterSelect({ label, value, onChange, options }: { label: string; value: string; onChange: (value: string) => void; options: Array<{ value: string; label: string }> }) {
  return <label className="grid grid-cols-[88px_1fr] items-center gap-2 text-[11px] text-[#aeb8c8]"><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)} className="min-w-0 rounded-md border border-white/10 bg-[#08111f] px-2 py-1.5 text-xs text-[#e8eaf0]"><option value="">Wszystkie</option>{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
}

function localDateTime(value: Date): string {
  const offset = value.getTimezoneOffset() * 60_000
  return new Date(value.getTime() - offset).toISOString().slice(0, 16)
}

function MessageAttachments({ attachments }: { attachments?: InboxMessage["attachments"] }) {
  if (!attachments?.length) return null
  return <div className="mt-3 flex flex-wrap gap-2">{attachments.map((attachment) => attachment.downloadUrl ? <a key={attachment.id} href={attachment.downloadUrl} download className="inline-flex items-center gap-1.5 rounded-md border border-white/10 bg-black/10 px-2.5 py-1.5 text-xs text-violet-200 hover:bg-white/5"><Paperclip className="size-3.5" /> {attachment.fileName} · {attachment.size}</a> : <span key={attachment.id} className="inline-flex items-center gap-1.5 rounded-md border border-white/10 px-2.5 py-1.5 text-xs text-[#cbd3df]"><Paperclip className="size-3.5" /> {attachment.fileName} · {attachment.size}</span>)}</div>
}

function Avatar({ initials, size = "default", online = false }: { initials: string; size?: "default" | "sm" | "xs"; online?: boolean }) {
  return (
    <span className={cn("relative grid shrink-0 place-items-center rounded-full bg-[linear-gradient(145deg,#6331e9,#3f16a8)] font-semibold text-white", size === "default" && "size-[43px] text-[16px]", size === "sm" && "size-[32px] text-[12px]", size === "xs" && "size-[27px] text-[10px]")}>
      {initials}
      {online && <span className="absolute bottom-0 right-0 size-2.5 rounded-full border-2 border-[#0d1624] bg-emerald-400" />}
    </span>
  )
}

function SlackMark() {
  return (
    <span className="grid size-[14px] grid-cols-2 gap-[1.5px]" aria-hidden>
      <span className="rounded-sm bg-[#36c5f0]" /><span className="rounded-sm bg-[#2eb67d]" />
      <span className="rounded-sm bg-[#e01e5a]" /><span className="rounded-sm bg-[#ecb22e]" />
    </span>
  )
}
