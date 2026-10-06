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
  UserRound,
  X,
} from "lucide-react"
import type { InboxCase, InboxMessage } from "@/lib/domain/inbox"
import { inboxStatusLabels } from "@/lib/domain/inbox"
import { deliveryStatusLabels } from "@/lib/domain/labels"
import { useCurrentUser, useInboxCase, useInboxCases, useInboxMessages, useInboxWorkflow, useMarkInboxCaseRead } from "@/lib/services/queries"
import type { InboxPendingAttachment } from "@/lib/services/inbox"
import { cn } from "@/lib/utils"
import { SafeExternalMessage } from "./safe-external-message"

type QuickFilter = "all" | "sla" | "mine"
type AdvancedFilter = "mine" | "unassigned" | "sla" | "unread"

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
  const casesQuery = useInboxCases()
  const currentUserQuery = useCurrentUser()
  const markRead = useMarkInboxCaseRead()
  const [selectedCaseId, setSelectedCaseId] = useState<string | undefined>(initialCaseId)
  const [mobileConversationOpen, setMobileConversationOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [quickFilter, setQuickFilter] = useState<QuickFilter>(onlyMine ? "mine" : "all")
  const [filterMenuOpen, setFilterMenuOpen] = useState(false)
  const [advancedFilters, setAdvancedFilters] = useState<AdvancedFilter[]>([])
  const filterMenuRef = useRef<HTMLDivElement>(null)
  const readAttempt = useRef<string | null>(null)

  const records = useMemo(() => casesQuery.data?.pages.flatMap((page) => page.items) ?? [], [casesQuery.data])
  const selectedId = selectedCaseId ?? records[0]?.id
  const selectedRecord = records.find((item) => item.id === selectedId)
  const detailQuery = useInboxCase(selectedId)
  const messagesQuery = useInboxMessages(selectedId)
  const workflow = useInboxWorkflow(selectedId)
  const presentations = useMemo(() => records.map((record) => toPresentation(record, currentUserQuery.data?.id)), [records, currentUserQuery.data?.id])
  const selectedPresentation = presentations.find((item) => item.id === selectedId)
    ?? (detailQuery.data ? toPresentation(detailQuery.data, currentUserQuery.data?.id) : undefined)
  const messages = useMemo(() => {
    const seen = new Set<string>()
    const newestFirst: InboxMessage[] = []
    for (const page of messagesQuery.data?.pages ?? []) {
      for (const message of page.items) {
        if (seen.has(message.id)) continue
        seen.add(message.id)
        newestFirst.push(message)
      }
    }
    return newestFirst.reverse()
  }, [messagesQuery.data])
  const latestMessageId = messagesQuery.data?.pages[0]?.items[0]?.id

  const visibleCases = useMemo(() => {
    const normalized = search.trim().toLocaleLowerCase("pl")
    return presentations.filter((item) => {
      if (normalized && !`${item.reference} ${item.company} ${item.subject} ${item.source}`.toLocaleLowerCase("pl").includes(normalized)) return false
      if (quickFilter === "sla" && !item.breached) return false
      if (quickFilter === "mine" && !item.mine) return false
      if (advancedFilters.includes("mine") && !item.mine) return false
      if (advancedFilters.includes("sla") && !item.breached) return false
      if (advancedFilters.includes("unread") && !item.unread) return false
      if (advancedFilters.includes("unassigned") && !item.unassigned) return false
      return true
    })
  }, [advancedFilters, quickFilter, presentations, search])

  useEffect(() => {
    const handlePointerDown = (event: PointerEvent) => {
      if (!filterMenuRef.current?.contains(event.target as Node)) setFilterMenuOpen(false)
    }
    document.addEventListener("pointerdown", handlePointerDown)
    return () => document.removeEventListener("pointerdown", handlePointerDown)
  }, [])

  useEffect(() => {
    const key = selectedRecord && latestMessageId ? `${selectedRecord.id}:${latestMessageId}` : null
    if (selectedRecord?.unreadForCurrentUser && latestMessageId && key !== readAttempt.current) {
      readAttempt.current = key
      markRead.mutate({ caseId: selectedRecord.id, messageId: latestMessageId }, {
        onError: () => { readAttempt.current = null },
      })
    }
  }, [selectedRecord, latestMessageId, markRead])

  const selectCase = (item: CasePresentation) => {
    setSelectedCaseId(item.id)
    setMobileConversationOpen(true)
  }

  const toggleAdvancedFilter = (filter: AdvancedFilter) => {
    setAdvancedFilters((current) =>
      current.includes(filter) ? current.filter((item) => item !== filter) : [...current, filter],
    )
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
                <div className="absolute right-0 top-12 z-30 w-52 rounded-xl border border-white/10 bg-[#111b29] p-2 shadow-2xl">
                  {([
                    ["mine", "Moje"],
                    ["unassigned", "Nieprzypisane"],
                    ["sla", "SLA"],
                    ["unread", "Nieodczytane"],
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
        loadingOlderMessages={messagesQuery.isFetchingNextPage}
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
  item, record, detailError, messages, messagesLoading, messagesError, hasOlderMessages, loadingOlderMessages, loadOlderMessages, workflow, onBack, className,
}: {
  item: CasePresentation
  record?: InboxCase
  detailError: boolean
  messages: InboxMessage[]
  messagesLoading: boolean
  messagesError: boolean
  hasOlderMessages: boolean
  loadingOlderMessages: boolean
  loadOlderMessages: () => void
  workflow: ReturnType<typeof useInboxWorkflow>
  onBack: () => void
  className: string
}) {
  const [draft, setDraft] = useState("")
  const [sendError, setSendError] = useState("")
  const idempotencyKey = useRef<string | null>(null)
  const canClaim = Boolean(record?.availableActions?.includes("CLAIM"))
  const canReply = Boolean(record?.availableActions?.includes("REPLY"))

  const send = async () => {
    if (!canReply || !draft.trim() || workflow.sendMessage.isPending) return
    idempotencyKey.current ??= crypto.randomUUID()
    setSendError("")
    try {
      await workflow.sendMessage.mutateAsync({ body: draft, idempotencyKey: idempotencyKey.current })
      setDraft("")
      idempotencyKey.current = null
    } catch (error) {
      setSendError(error instanceof Error ? error.message : "Nie udało się wysłać odpowiedzi.")
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
            <button disabled title="Akcja niedostępna" className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#7f899a] opacity-75"><AlarmClock className="size-[17px]" /> Odłóż</button>
            <button disabled title="Akcja niedostępna" className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#7f899a] opacity-75"><Check className="size-[17px]" /> Zamknij sprawę</button>
          </div>
        </div>
        <div className="mt-[15px] flex items-center gap-3">
          <button type="button" onClick={() => workflow.claim.mutate()} disabled={!canClaim || workflow.claim.isPending} className="h-[45px] rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3 text-[12px] text-[#edf0f4] disabled:cursor-not-allowed disabled:opacity-50 xl:hidden">Przejmij</button>
          <div className="flex h-[45px] items-center gap-2 rounded-[9px] border border-violet-500/[0.12] bg-violet-950/35 px-3.5 text-[12px] font-medium text-violet-300">{item.status}</div>
          <div className="flex h-[45px] items-center gap-2.5 rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3.5 text-[12px] text-[#edf0f4]"><Avatar initials={initials(record?.owner?.fullName ?? "?")} size="sm" />{record?.owner?.fullName ?? "Nieprzypisane"}</div>
          <div className="flex h-[45px] items-center gap-2.5 rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3.5 text-[12px] font-semibold text-[#aeb8c8]"><Clock3 className="size-[17px]" />{item.sla}</div>
        </div>
        {detailError && <p role="alert" className="text-xs text-red-400">Nie udało się wczytać szczegółów case’u.</p>}
        {workflow.claim.isError && <p role="alert" className="text-xs text-red-400">{workflow.claim.error.message}</p>}
      </header>

      <div className="cases-scrollbar min-h-0 flex-1 overflow-y-auto bg-[radial-gradient(circle_at_72%_34%,rgba(23,48,76,0.12),transparent_42%)] py-[22px] pl-[22px] pr-[12px]">
        {hasOlderMessages && <button type="button" onClick={loadOlderMessages} className="mb-5 text-sm text-violet-300">Wczytaj starsze wiadomości</button>}
        {messagesLoading ? <p className="text-sm text-[#9aa5b6]">Wczytywanie wiadomości…</p> : messagesError ? <p role="alert" className="text-sm text-red-400">Nie udało się wczytać wiadomości.</p> : messages.length === 0 ? <p className="text-sm text-[#9aa5b6]">Brak wiadomości.</p> : (
          <div className="flex flex-col gap-6">
            {messages.map((message) => message.kind === "support" ? (
              <div key={message.id} className="flex justify-end"><div className="max-w-[570px] rounded-[12px] bg-[linear-gradient(135deg,rgba(52,28,104,0.86),rgba(32,24,73,0.9))] px-4 py-3 text-sm text-[#ded9eb]"><div className="mb-2 text-xs text-[#cfc4e8]">{message.sender ?? "Wsparcie"} · {timeLabel(message.createdAt)}{message.deliveryStatus ? ` · ${message.deliveryStatus}` : ""}</div><p className="whitespace-pre-wrap"><SafeExternalMessage content={message.body} /></p></div></div>
            ) : message.kind === "system" ? (
              <div key={message.id} className="text-center text-xs text-[#9ba6b6]">{timeLabel(message.createdAt)} · <SafeExternalMessage content={message.body} /></div>
            ) : (
              <div key={message.id} className="flex items-start gap-4"><Avatar initials={initials(message.sender ?? item.company)} /><div className="max-w-[570px]"><div className="text-xs text-[#aeb8c8]">{message.sender ?? item.company} · {timeLabel(message.createdAt)}</div><p className="mt-2 whitespace-pre-wrap text-[14px] leading-6 text-[#edf0f4]"><SafeExternalMessage content={message.body} /></p></div></div>
            ))}
          </div>
        )}
      </div>

      <div className="shrink-0 pb-[22px] pl-[18px] pr-[22px]">
        <div className="rounded-[11px] border border-white/[0.085] bg-[linear-gradient(110deg,#0d1725,#0b1522)]">
          <textarea value={draft} onChange={(event) => { setDraft(event.target.value); idempotencyKey.current = null }} disabled={!canReply} placeholder={canReply ? "Napisz odpowiedź..." : "Przejmij case, aby odpowiedzieć"} aria-label="Treść odpowiedzi" className="block h-[80px] w-full resize-none bg-transparent px-[23px] pt-[17px] text-[13px] text-[#eef0f4] outline-none placeholder:text-[#8e99aa] disabled:cursor-not-allowed" />
          <div className="flex h-[51px] items-center justify-end px-[19px]"><button type="button" onClick={send} disabled={!canReply || !draft.trim() || workflow.sendMessage.isPending} className="h-[45px] w-[117px] rounded-[9px] bg-[linear-gradient(135deg,#5b23e5,#4c17c9)] text-[12px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-50">{workflow.sendMessage.isPending ? "Wysyłanie…" : "Wyślij"}</button></div>
        </div>
        {sendError && <p role="alert" className="mt-2 text-sm text-red-400">{sendError}</p>}
      </div>
    </section>
  )
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
