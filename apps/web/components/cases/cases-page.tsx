"use client"

import Link from "next/link"
import { useRouter } from "next/navigation"
import { deliveryPresentation } from "@/lib/i18n/delivery-presentation"
import { MessageAttachments } from "./message-attachments"
import { attachmentLimitError } from "@/lib/services/attachment-limits"
import { userError } from "@/lib/i18n/errors"
import { formatDuration } from "@/lib/format"
import { locale } from "@/lib/i18n/catalog"
import { useT } from "@/lib/i18n/provider"
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react"
import {
  AlarmClock,
  ArrowLeft,
  Check,
  PanelRight,
  Send,
  UsersRound,
  ListChecks,
  Paperclip,
  PanelLeft,
  RotateCcw,
  Search,
  SlidersHorizontal,
  ThumbsDown,
  UserRound,
} from "lucide-react"
import type { InboxActivity, InboxCase, InboxMessage, InboxWorkflowStatus } from "@/lib/domain/inbox"
import { inboxStatusLabels, inboxWorkflowStatusLabels } from "@/lib/domain/inbox"
import { deliveryStatusLabels } from "@/lib/domain/labels"
import { MAX_BULK_SELECTION } from "@/lib/services/bulk-selection"
import type { InboxListQuery, InboxQuickView } from "@/lib/services/inbox"
import { useSelectMatchingCases, useCurrentUser, useInboxActivity, useInboxAssignees, useInboxCase, useInboxCases, useInboxFilterOptions, useInboxMessages, useInboxWorkflow, useMarkAllResolvedRead, useMarkInboxCaseRead } from "@/lib/services/queries"
import type { InboxSendInput } from "@/lib/services/inbox"
import { cn } from "@/lib/utils"
import { useShell } from "@/components/layout/shell-context"
import { Button } from "@/components/ui/button"
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet"
import { BulkCaseToolbar } from "./bulk-case-toolbar"
import { slackMessagePreview } from "@/lib/security/external-message"
import { SafeExternalMessage } from "./safe-external-message"

const quickViews = [
  ["unassigned", "Nieprzypisane"], ["mine", "Moje"], ["sla", "SLA"], ["all", "Wszystkie"],
] as const

const wideInboxQuery = "(min-width: 1280px)"
function subscribeToWideInbox(update: () => void) {
  const media = window.matchMedia(wideInboxQuery)
  media.addEventListener("change", update)
  return () => media.removeEventListener("change", update)
}
function readWideInbox() {
  return window.matchMedia(wideInboxQuery).matches
}
type AdvancedFilter = "mine" | "unassigned" | "sla" | "unread" | "snoozed" | "active" | "terminal"

type CasePresentation = {
  id: string
  reference: string
  author: string
  company: string
  owner: string
  terminal: boolean
  preview: string
  time?: string
  platform: "Slack" | "Teams" | "Telegram"
  source: string
  status: string
  statusTone: "red" | "blue" | "purple" | "green" | "gray"
  sla: string
  slaTitle?: string
  slaTone: "red" | "amber" | "green" | "gray"
  unread: boolean
  breached: boolean
  unassigned: boolean
}

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0].toLocaleUpperCase("pl")).join("") || "?"
}

function timeLabel(value: string): string {
  return new Intl.DateTimeFormat(locale(), { dateStyle: "short", timeStyle: "short" }).format(new Date(value))
}

function toPresentation(record: InboxCase, t: ReturnType<typeof useT>): CasePresentation {
  const statusTone = record.status === "resolved" || record.status === "ignored" ? "gray" : record.status === "new" ? "green" : record.status === "verification" ? "purple" : record.status === "waiting_for_customer" ? "blue" : "red"
  const terminal = record.status === "resolved" || record.status === "ignored"
  const paused = record.sla.state === "paused"
  const slaTone = terminal || paused || record.sla.state === "unknown" ? "gray" : record.sla.state === "breached" ? "red" : record.sla.state === "at_risk" ? "amber" : "green"
  const dueAt = record.sla.dueAt
  const remaining = record.sla.remainingBusinessSeconds ?? (dueAt ? (Date.parse(dueAt) - Date.now()) / 1000 : 0)
  const overdue = record.sla.state === "breached"
  const clock = record.sla.kind ?? "SLA"
  const countdown = new Intl.NumberFormat(locale(), { style: "unit", unit: "minute", unitDisplay: "short" })
    .format(Math.ceil(Math.max(0, overdue ? -remaining : remaining) / 60))
  const company = record.customer.id === "unassigned" ? record.sourceChannel : record.customer.name
  const bodyPreview = record.lastMessagePreview.trim()
  const preview = bodyPreview
    ? record.platform === "slack" ? slackMessagePreview(bodyPreview) : bodyPreview
    : record.lastMessageFile ? t("Plik: {{value0}}", { value0: record.lastMessageFile }) : ""
  return {
    id: record.id,
    reference: record.reference,
    author: record.lastMessageAuthor || t(record.lastMessageKind === "support" ? "Wsparcie" :
      record.lastMessageKind === "system" ? "System" : "Klient"),
    company,
    owner: record.owner?.fullName ?? t("Nieprzypisane"),
    terminal,
    preview,
    time: record.lastMessageAt ?? (record.lastMessageId || bodyPreview ? record.createdAt : undefined),
    platform: record.platform === "slack" ? "Slack" : record.platform === "teams" ? "Teams" : "Telegram",
    source: record.sourceChannel,
    status: t(record.workflowStatus ? inboxWorkflowStatusLabels[record.workflowStatus] : inboxStatusLabels[record.status]),
    statusTone,
    sla: paused && !terminal ? `SLA · ${t("Wstrzymane")}` : dueAt && !terminal
      ? `${clock} · ${overdue ? t("Po terminie o {{duration}}", { duration: countdown }) : countdown}`
      : t("SLA —"),
    slaTitle: dueAt && !terminal ? timeLabel(dueAt) : undefined,
    slaTone,
    unread: record.unreadForCurrentUser,
    breached: slaTone === "red",
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
  const t = useT()
  const shell = useShell()
  const router = useRouter()
  const [selectedCaseId, setSelectedCaseId] = useState<string | undefined>(initialCaseId)
  const [mobileConversationOpen, setMobileConversationOpen] = useState(Boolean(initialCaseId))
  const [desktopDetailsOpen, setDesktopDetailsOpen] = useState(true)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const wideInbox = useSyncExternalStore(subscribeToWideInbox, readWideInbox, () => false)
  const detailsToggleRef = useRef<HTMLButtonElement>(null)
  const conversationTitleRef = useRef<HTMLHeadingElement>(null)
  const lastCardRef = useRef<HTMLButtonElement | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const detailsOpen = wideInbox ? desktopDetailsOpen : drawerOpen
  const [search, setSearch] = useState("")
  const [quickFilter, setQuickFilter] = useState<InboxQuickView>(onlyMine ? "mine" : "all")
  const [bulkMode, setBulkMode] = useState(false)
  const bulkModeVersion = useRef(0)
  const [filterMenuOpen, setFilterMenuOpen] = useState(false)
  const [advancedFilters, setAdvancedFilters] = useState<AdvancedFilter[]>([])
  const [serverSearch, setServerSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState<InboxWorkflowStatus | "">("")
  const [providerFilter, setProviderFilter] = useState<"slack" | "teams" | "telegram" | "">("")
  const [ownerFilter, setOwnerFilter] = useState("")
  const filterMenuRef = useRef<HTMLDivElement>(null)
  const readAttempt = useRef<string | null>(null)
  const listQuery = useMemo<InboxListQuery>(() => ({
    view: advancedFilters.includes("snoozed") ? "snoozed" : "active",
    quickView: quickFilter,
    mine: advancedFilters.includes("mine"),
    unassigned: advancedFilters.includes("unassigned"),
    unread: advancedFilters.includes("unread"),
    slaAttention: advancedFilters.includes("sla"),
    statusScope: advancedFilters.includes("terminal") ? "terminal" : advancedFilters.includes("active") ? "active" : "all",
    workflowStatus: statusFilter || undefined,
    actionableQueue: true,
    provider: providerFilter || undefined,
    ownerId: ownerFilter || undefined,
    search: serverSearch || undefined,
  }), [advancedFilters, ownerFilter, providerFilter, quickFilter, serverSearch, statusFilter])
  const scope = JSON.stringify(listQuery)
  const [selection, setSelection] = useState<{ scope: string; ids: string[] }>({ scope, ids: [] })
  if (selection.scope !== scope) setSelection({ scope, ids: [] })
  const selectionIds = selection.scope === scope ? selection.ids : []
  const matching = useSelectMatchingCases()
  const selectionScope = useRef(scope)
  useEffect(() => { selectionScope.current = scope }, [scope])
  const casesQuery = useInboxCases(listQuery)
  const filterOptions = useInboxFilterOptions()
  const markRead = useMarkInboxCaseRead()
  const markResolvedRead = useMarkAllResolvedRead()

  const records = useMemo(() => {
    const seen = new Set<string>()
    return (casesQuery.data?.pages.flatMap((page) => page.items) ?? []).filter((item) => {
      if (seen.has(item.id)) return false
      seen.add(item.id)
      return true
    })
  }, [casesQuery.data])
  if (selectedCaseId === undefined && records[0]?.id) {
    setSelectedCaseId(records[0].id)
  }
  const selectedId = selectedCaseId ?? records[0]?.id
  const detailQuery = useInboxCase(selectedId)
  const messagesQuery = useInboxMessages(selectedId)
  const workflow = useInboxWorkflow(selectedId)
  const presentations = useMemo(() => records.map((record) => toPresentation(record, t)), [records, t])
  const selectedPresentation = presentations.find((item) => item.id === selectedId)
    ?? (detailQuery.data ? toPresentation(detailQuery.data, t) : undefined)
  const messages = useMemo(() => {
    const seen = new Set<string>()
    return (messagesQuery.data?.pages.flatMap((page) => page.items) ?? [])
      .filter((message) => { if (seen.has(message.id)) return false; seen.add(message.id); return true }).reverse()
  }, [messagesQuery.data])
  const latestMessageId = messages.findLast((message) => message.kind !== "internal_note")?.id

  const counts = casesQuery.data?.pages[0]?.quickViewCounts
  const activity = useInboxActivity(detailsOpen ? selectedId : undefined)
  const activityItems = activity.data?.pages.flatMap((page) => page.items) ?? []

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
    if ((mobileConversationOpen || window.matchMedia("(min-width: 768px)").matches) && selectedId && latestMessageId && key !== readAttempt.current) {
      readAttempt.current = key
      markRead.mutate({ caseId: selectedId, messageId: latestMessageId }, {
        onError: () => { readAttempt.current = null },
      })
    }
  }, [selectedId, latestMessageId, markRead, mobileConversationOpen])

  const toggleBulkMode = () => {
    bulkModeVersion.current++
    matching.reset()
    setSelection({ scope, ids: [] })
    setBulkMode(!bulkMode)
  }

  const selectAllMatching = () => {
    const version = bulkModeVersion.current
    matching.mutate(listQuery, {
      onSuccess: (ids) => {
        if (selectionScope.current === scope && bulkModeVersion.current === version) setSelection({ scope, ids })
      },
    })
  }

  const selectCase = (item: CasePresentation, trigger: HTMLButtonElement) => {
    lastCardRef.current = trigger
    setSelectedCaseId(item.id)
    setMobileConversationOpen(true)
    if (!window.matchMedia("(min-width: 768px)").matches) {
      requestAnimationFrame(() => conversationTitleRef.current?.focus())
    }
  }

  const returnToQueue = () => {
    setDrawerOpen(false)
    setMobileConversationOpen(false)
    requestAnimationFrame(() => (lastCardRef.current?.isConnected ? lastCardRef.current : searchRef.current)?.focus())
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
    <main className={cn("cases-workspace grid min-h-0 flex-1 grid-cols-1 overflow-hidden text-[#f4f4f5]",
      "md:grid-cols-[290px_minmax(0,1fr)] lg:grid-cols-[360px_minmax(0,1fr)]",
      wideInbox && desktopDetailsOpen && selectedPresentation && "xl:grid-cols-[360px_minmax(500px,1fr)_310px]")}>
      <section
        className={cn(
          "min-h-0 min-w-0 flex-col border-r border-white/[0.09] bg-[#0b1422]",
          mobileConversationOpen ? "hidden md:flex" : "flex",
        )}
        aria-label={t("Lista czatów")}
      >
        <div className="shrink-0 px-4 pt-[20px] pb-[14px]">
          <div className="flex min-h-8 flex-wrap items-center gap-2.5">
            <Button variant="ghost" size="icon-sm" className="lg:hidden" onClick={() => shell?.openMobileNav()} aria-label={t("Przełącz panel boczny")}>
              <PanelLeft />
            </Button>
            <h1 className="text-[21px] font-bold tracking-[-0.02em]">{t("Czaty")}</h1>
            <span aria-label={t("Liczba spraw")} className="rounded-full bg-[#151f2e] px-2 py-0.5 text-[12px] font-semibold text-[#d8dce4]">{counts?.[quickFilter] ?? "—"}</span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              aria-pressed={bulkMode}
              onClick={toggleBulkMode}
              className="ml-auto border-white/10 bg-[#111b29] text-[#d8dce4] aria-pressed:border-violet-500/60 aria-pressed:bg-violet-500/20"
            >
              <ListChecks />{t("Operacje zbiorcze")}
            </Button>
          </div>

          <div className="mt-[17px] flex gap-3">
            <label className="flex h-[42px] min-w-0 flex-1 items-center gap-2.5 rounded-[9px] border border-white/[0.07] bg-[#111b29] px-3 text-[#8f9aad] shadow-[inset_0_1px_0_rgba(255,255,255,0.015)] focus-within:border-violet-500/50">
              <Search className="size-[18px] shrink-0" strokeWidth={1.8} />
              <input
                ref={searchRef}
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                className="min-w-0 flex-1 bg-transparent text-[13px] text-[#e8eaf0] outline-none placeholder:text-[#8f9aad]"
                placeholder={t("Szukaj rozmów...")}
                aria-label={t("Szukaj rozmów")}
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
                aria-label={t("Filtry")}
                aria-expanded={filterMenuOpen}
                onKeyDown={(event) => { if (event.key === "Escape") setFilterMenuOpen(false) }}
              >
                <SlidersHorizontal className="size-[18px]" strokeWidth={1.6} />
              </button>
              {filterMenuOpen && (
                <div className="absolute right-0 top-12 z-30 w-[min(18rem,calc(100vw-2rem))] rounded-xl border border-white/10 bg-[#111b29] p-2 shadow-2xl">
                  {([
                    ["mine", t("Moje")],
                    ["unassigned", t("Nieprzypisane")],
                    ["sla", "SLA"],
                    ["unread", t("Nieodczytane")],
                    ["snoozed", t("Odłożone")],
                    ["active", t("Aktywne")],
                    ["terminal", t("Archiwum")],
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
                        <span className={value === "unread" ? "font-semibold" : undefined}>{t(label)}</span>
                      </button>
                    )
                  })}
                  <div className="mt-2 grid gap-2 border-t border-white/10 pt-2">
                    <FilterSelect label={t("Status")} value={statusFilter} onChange={(value) => setStatusFilter(value as InboxWorkflowStatus | "")} options={Object.entries(inboxWorkflowStatusLabels).map(([value, label]) => ({ value, label: t(label) }))} />
                    <FilterSelect label={t("Dostawca")} value={providerFilter} onChange={(value) => setProviderFilter(value as typeof providerFilter)} options={[{ value: "slack", label: "Slack" }, { value: "teams", label: "Teams" }, { value: "telegram", label: "Telegram" }]} />
                    <FilterSelect label={t("Właściciel")} value={ownerFilter} onChange={setOwnerFilter} options={(filterOptions.data?.owners ?? []).map((item) => ({ value: item.id, label: item.name }))} />
                  </div>
                  <button type="button" disabled={markResolvedRead.isPending} onClick={() => { if (window.confirm(t("Potwierdź oznaczenie wszystkich rozwiązanych spraw jako przeczytane."))) markResolvedRead.mutate() }} className="mt-1 w-full border-t border-white/10 px-2.5 py-2 text-left text-[12px] text-violet-300 disabled:opacity-50">{t("Oznacz rozwiązane jako przeczytane")}</button>
                </div>
              )}
            </div>
          </div>

          <div className="mt-[13px] grid grid-cols-2 gap-1.5" role="group" aria-label={t("Szybkie filtry")}>
            {quickViews.map(([view, label]) => (
              <QuickFilterButton key={view} label={t(label)} descriptionId={"quick-view-count-" + view} active={quickFilter === view} onClick={() => setQuickFilter(view)}>
                {t(label)} <span className="ml-auto tabular-nums" id={"quick-view-count-" + view} data-quick-view-count={view}>{counts?.[view] ?? "—"}</span>
              </QuickFilterButton>
            ))}
          </div>
        </div>

        <BulkCaseToolbar active={bulkMode} selected={selectionIds} references={new Map(records.map((item) => [item.id, item.reference]))} clear={() => setSelection({ scope, ids: [] })}>
          <div className="flex flex-wrap items-center gap-2 text-xs text-violet-300">
            <Button type="button" size="sm" variant="ghost" disabled={matching.isPending || records.length > MAX_BULK_SELECTION} aria-describedby="bulk-selection-limit" onClick={() => setSelection({ scope, ids: records.map((item) => item.id) })}>
              {t("Zaznacz widoczne (")}{records.length})
            </Button>
            <Button type="button" size="sm" variant="ghost" disabled={matching.isPending} onClick={selectAllMatching}>
              {matching.isPending ? t("Wczytywanie…") : t("Zaznacz wszystkie pasujące (do 1000)")}
            </Button>
            {(records.length > MAX_BULK_SELECTION || selectionIds.length >= MAX_BULK_SELECTION) && <p id="bulk-selection-limit" role="status" className="w-full text-[#b9c2d0]">{t("Limit zaznaczenia: 1000 spraw. Usuń zaznaczenie lub zawęź filtry.")}</p>}
            {matching.isError && <p role="alert">{userError(matching.error)}</p>}
          </div>
        </BulkCaseToolbar>
        <div className="cases-scrollbar min-h-0 flex-1 overflow-y-auto px-3 pb-3" role="group" aria-label={t("Rozmowy")}>
          {casesQuery.isLoading ? (
            <CaseListSkeleton />
          ) : casesQuery.isError ? (
            <div className="mt-10 px-5 text-center text-sm text-[#9aa5b6]">{t("Nie udało się wczytać rozmów.")}</div>
          ) : presentations.length ? (
            presentations.map((item) => (
              <div key={item.id} className={cn("relative rounded-lg", bulkMode && "pl-7", selectionIds.includes(item.id) && "bg-violet-500/15 ring-1 ring-inset ring-violet-500/50")}>
              {bulkMode && <input type="checkbox" aria-label={t("Zaznacz {{value0}}", { value0: item.reference })} checked={selectionIds.includes(item.id)} disabled={matching.isPending || (!selectionIds.includes(item.id) && selectionIds.length >= MAX_BULK_SELECTION)} aria-describedby="bulk-selection-limit" onChange={(event) => { if (event.target.checked && selectionIds.length >= MAX_BULK_SELECTION) return; setSelection({ scope, ids: event.target.checked ? [...new Set([...selectionIds, item.id])] : selectionIds.filter((id) => id !== item.id) }) }} className="absolute left-1 top-5 size-4 accent-violet-500" />}
              <CaseListItem
                item={item}
                selected={item.id === selectedId}
                unread={item.unread}
                onSelect={(trigger) => selectCase(item, trigger)}
              /></div>
            ))
          ) : (
            <div className="mt-10 px-5 text-center text-sm text-[#9aa5b6]">{t("Brak rozmów pasujących do filtrów.")}</div>
          )}
          {casesQuery.hasNextPage && <button type="button" onClick={() => casesQuery.fetchNextPage()} disabled={casesQuery.isFetchingNextPage} className="mx-5 mb-4 text-sm text-violet-300">{t("Wczytaj więcej")}</button>}
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
        onBack={returnToQueue}
        onRedirect={(caseId) => {
          setSelectedCaseId(caseId)
          router.replace("?caseId=" + encodeURIComponent(caseId), { scroll: false })
        }}
        detailsOpen={detailsOpen}
        onToggleDetails={() => wideInbox ? setDesktopDetailsOpen(!desktopDetailsOpen) : setDrawerOpen(!drawerOpen)}
        detailsToggleRef={detailsToggleRef}
        conversationTitleRef={conversationTitleRef}
        className={mobileConversationOpen ? "flex" : "hidden md:flex"}
      /> : <section className="hidden min-h-0 items-center justify-center bg-[#08111f] text-sm text-[#9aa5b6] lg:flex">{casesQuery.isError ? t("Nie udało się wczytać rozmowy.") : t("Wybierz rozmowę")}</section>}
      {selectedPresentation && (wideInbox ? desktopDetailsOpen && (
        <aside id="case-details" aria-label={t("Szczegóły sprawy")} className="min-h-0 min-w-0 overflow-y-auto border-l border-white/[0.09] bg-[#0b1422]">
          <div className="flex items-center justify-between gap-3 px-4 pt-4">
            <h2 className="text-base font-semibold">{t("Szczegóły sprawy")}</h2>
            <Button type="button" variant="ghost" size="icon-sm" aria-label={t("Zwiń szczegóły")} onClick={() => {
              setDesktopDetailsOpen(false)
              detailsToggleRef.current?.focus()
            }}><PanelRight /></Button>
          </div>
          <CaseDetails item={selectedPresentation} record={detailQuery.data} activity={activity} items={activityItems} />
        </aside>
      ) : (
        <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
          <SheetContent id="case-details" className="w-full max-w-[320px] gap-0 overflow-y-auto border-white/[0.09] bg-[#0b1422] text-[#f4f4f5] sm:max-w-[320px]" finalFocus={detailsToggleRef}>
            <SheetTitle className="px-4 pt-5 text-[#f4f4f5]">{t("Szczegóły sprawy")}</SheetTitle>
            <CaseDetails item={selectedPresentation} record={detailQuery.data} activity={activity} items={activityItems} />
          </SheetContent>
        </Sheet>
      ))}
    </main>
  )
}

function QuickFilterButton({ active, label, descriptionId, onClick, children }: { active: boolean; label: string; descriptionId: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      aria-label={label}
      aria-describedby={descriptionId}
      className={cn(
        "flex h-8 items-center gap-1.5 rounded-[7px] border px-2 text-[11px] font-medium transition",
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
  onSelect: (trigger: HTMLButtonElement) => void
}) {
  const t = useT()
  return (
    <button
      type="button"
      aria-pressed={selected}
      data-case-id={item.id}
      onClick={(event) => onSelect(event.currentTarget)}
      className={cn(
        "group relative mb-[6px] flex w-full overflow-hidden rounded-[9px] border px-3 py-2.5 text-left transition-colors",
        item.terminal
          ? cn("border-white/[0.075] bg-[#0f1927]", selected && "border-slate-400/40")
          : item.breached
          ? cn("border-red-500/45 bg-[linear-gradient(105deg,rgba(89,24,32,0.52),rgba(54,20,31,0.48))] hover:border-red-400/60", selected && "ring-1 ring-inset ring-violet-300/60")
          : selected
            ? "border-violet-500/45 bg-[linear-gradient(105deg,rgba(48,30,88,0.7),rgba(25,25,54,0.72))] shadow-[inset_0_0_22px_rgba(91,33,232,0.08)]"
            : unread
              ? "border-violet-500/25 bg-[linear-gradient(105deg,rgba(29,29,57,0.92),rgba(24,26,45,0.94))] hover:border-violet-500/40"
              : "border-white/[0.075] bg-[#0f1927] hover:border-white/[0.15] hover:bg-[#121d2c]",
      )}
    >
      <span className={cn("absolute inset-y-0 left-0 w-1", item.terminal || item.slaTone === "gray" ? "bg-slate-500/50" : item.breached ? "bg-red-500" : item.slaTone === "amber" ? "bg-amber-500" : item.slaTone === "green" ? "bg-emerald-500" : "bg-violet-500")} />
      <span className="min-w-0 flex-1">
        <span className="flex min-w-0 items-center gap-2">
          <span className="grid size-6 shrink-0 place-items-center rounded bg-white/[0.05]" role="img" aria-label={item.platform}>
            {item.platform === "Slack" ? <SlackMark /> : item.platform === "Teams" ? <UsersRound className="size-3.5 text-violet-300" /> : <Send className="size-3.5 text-sky-300" />}
          </span>
          <span className={cn("truncate text-sm", unread ? "font-bold text-white" : "text-[#eff0f4]")} title={item.company}>{item.company}</span>
          {item.time && <time dateTime={item.time} className="ml-auto shrink-0 text-[10px] tabular-nums text-[#9ba5b5]">{timeLabel(item.time)}</time>}
          {unread && <span className="size-2 shrink-0 rounded-full bg-[#7650ff]" aria-label={t("Nieodczytane")} />}
        </span>
        <span className="mt-1 block truncate text-[12px] text-[#aeb8c8]" title={item.preview}>
          {item.preview && <><span className="font-medium text-[#edf0f4]" data-case-preview-author>{item.author}</span> · {item.preview}</>}
        </span>
        <span className="mt-2 flex min-w-0 items-center justify-between gap-2">
          <span className="flex min-w-0 items-center gap-1.5" title={item.owner}>
            {!item.unassigned && <Avatar initials={initials(item.owner)} size="xs" />}
            <span data-case-owner className={cn("truncate text-[11px] text-[#cbd3df]", item.unassigned && "rounded bg-white/[0.06] px-1.5 py-0.5")}>{item.owner}</span>
          </span>
          <SlaBadge tone={item.slaTone} title={item.slaTitle}>{item.sla}</SlaBadge>
        </span>
      </span>
    </button>
  )
}

function StatusBadge({ tone, children }: { tone: CasePresentation["statusTone"]; children: React.ReactNode }) {
  const colors = {
    gray: "bg-slate-500/15 text-slate-300",
    red: "bg-red-500/[0.10] text-red-400",
    blue: "bg-sky-500/[0.12] text-sky-400",
    purple: "bg-violet-500/[0.13] text-violet-300",
    green: "bg-emerald-500/[0.12] text-emerald-400",
  }
  return <span className={cn("rounded-[6px] px-2.5 py-[5px] text-[11px] font-medium leading-none", colors[tone])}>{children}</span>
}

function SlaBadge({ tone, children, title }: { tone: CasePresentation["slaTone"]; children: React.ReactNode; title?: string }) {
  const colors = {
    red: "bg-red-500/[0.10] text-red-400",
    amber: "bg-amber-500/[0.10] text-amber-400",
    green: "bg-emerald-500/[0.09] text-emerald-400",
    gray: "bg-slate-500/10 text-slate-300",
  }
  return <span title={title} className={cn("shrink-0 rounded-[6px] px-2 py-[5px] text-[11px] font-medium leading-none", colors[tone])}>{children}</span>
}

function ConversationPanel({
  item, record, detailError, messages, messagesLoading, messagesError, hasOlderMessages, loadOlderMessages, workflow, onBack, onRedirect, detailsOpen, onToggleDetails, detailsToggleRef, conversationTitleRef, className,
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
  onRedirect: (caseId: string) => void
  detailsOpen: boolean
  onToggleDetails: () => void
  detailsToggleRef: React.RefObject<HTMLButtonElement | null>
  conversationTitleRef: React.RefObject<HTMLHeadingElement | null>
  className: string
}) {
  const t = useT()
  const [draft, setDraft] = useState("")
  const [noteDraft, setNoteDraft] = useState("")
  const [noteMode, setNoteMode] = useState(false)
  const [noteError, setNoteError] = useState<Error | null>(null)
  const noteKey = useRef<string | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [sendError, setSendError] = useState<Error | null>(null)
  const [actionDialog, setActionDialog] = useState<"ask" | "ignore" | "resolve" | "snooze" | "reopen" | "admin" | null>(null)
  const [actionText, setActionText] = useState("")
  const [resolutionCategory, setResolutionCategory] = useState("")
  const [waitingMinutes, setWaitingMinutes] = useState(24 * 60)
  const [snoozeUntil, setSnoozeUntil] = useState(() => localDateTime(new Date(Date.now() + 60 * 60_000)))
  const [assigneeId, setAssigneeId] = useState("")
  const [actionError, setActionError] = useState<Error | null>(null)
  const sending = useRef(false)
  const composing = useRef(false)
  const submission = useRef<InboxSendInput | null>(null)
  const workflowActionKey = useRef<string | null>(null)
  const canClaim = Boolean(record?.availableActions?.includes("CLAIM"))
  const canReply = Boolean(record?.availableActions?.includes("REPLY"))
  const canAsk = Boolean(record?.availableActions?.includes("ASK_CUSTOMER"))
  const canIgnore = Boolean(record?.availableActions?.includes("IGNORE"))
  const canResolve = Boolean(record?.availableActions?.includes("RESOLVE"))
  const canReopen = Boolean(record?.availableActions?.includes("REOPEN"))
  const canSnooze = Boolean(record?.availableActions?.includes("SNOOZE"))
  const canAdmin = Boolean(record?.availableActions?.some((action) => ["ASSIGN", "REASSIGN", "UNASSIGN", "FORCE_RESOLVE"].includes(action)))
  const assignees = useInboxAssignees(record?.id, actionDialog === "admin")
  const { data: currentUser } = useCurrentUser()
  const canManageIntegrations = currentUser?.role === "admin" && currentUser.effectivePermissions.includes("manage_integrations")
  const availableAssignees = (assignees.data ?? []).filter((agent) => agent.eligible && agent.id !== record?.owner?.id)
  const validAssignee = availableAssignees.some((agent) => agent.id === assigneeId)
  const actionPending = workflow.askCustomer.isPending
    || workflow.ignore.isPending
    || workflow.resolve.isPending
    || workflow.snooze.isPending
    || workflow.assign.isPending
    || workflow.unassign.isPending
    || workflow.forceResolve.isPending
    || workflow.reopen.isPending

  const hasContent = noteMode ? Boolean(noteDraft.trim()) : Boolean(draft.trim() || files.length)
  const submitting = workflow.sendMessage.isPending || workflow.addInternalNote.isPending

  const addFiles = (selected: File[]) => {
    if (!canReply || noteMode || sending.current || !selected.length) return
    const next = [...files, ...selected]
    const error = attachmentLimitError(next)
    setSendError(error ?? null)
    if (error) return
    setFiles(next)
    submission.current = null
  }

  const send = async () => {
    if (!canReply || !hasContent || sending.current || submitting) return
    sending.current = true
    if (noteMode) {
      noteKey.current ??= crypto.randomUUID()
      setNoteError(null)
      try {
        await workflow.addInternalNote.mutateAsync({ body: noteDraft, idempotencyKey: noteKey.current })
        setNoteDraft("")
        noteKey.current = null
      } catch (error) {
        setNoteError(error instanceof Error ? error : new Error(t("Nie udało się zapisać notatki.")))
      } finally {
        sending.current = false
      }
      return
    }
    submission.current ??= { body: draft, attachments: files, idempotencyKey: crypto.randomUUID() }
    setSendError(null)
    try {
      await workflow.sendMessage.mutateAsync(submission.current)
      setDraft("")
      setFiles([])
      submission.current = null
    } catch (error) {
      setSendError(error instanceof Error ? error : new Error(t("Nie udało się wysłać odpowiedzi.")))
    } finally {
      sending.current = false
    }
  }

  const performAction = async () => {
    if (actionPending) return
    setActionError(null)
    try {
      if (actionDialog === "ask") {
        workflowActionKey.current ??= crypto.randomUUID()
        await workflow.askCustomer.mutateAsync({ message: actionText, waitingMinutes, idempotencyKey: workflowActionKey.current })
      } else if (actionDialog === "ignore") {
        await workflow.ignore.mutateAsync({ reason: actionText.trim() || undefined })
      } else if (actionDialog === "resolve") {
        workflowActionKey.current ??= crypto.randomUUID()
        await workflow.resolve.mutateAsync({ category: resolutionCategory || undefined, idempotencyKey: workflowActionKey.current })
      } else if (actionDialog === "reopen") {
        workflowActionKey.current ??= crypto.randomUUID()
        const result = await workflow.reopen.mutateAsync(workflowActionKey.current)
        if (result.caseId !== item.id) onRedirect(result.caseId)
      } else if (actionDialog === "snooze") {
        await workflow.snooze.mutateAsync(new Date(snoozeUntil).toISOString())
      }
      setActionDialog(null)
      setActionText("")
      workflowActionKey.current = null
    } catch (error) {
      setActionError(error instanceof Error ? error : new Error(t("Nie udało się wykonać akcji.")))
    }
  }

  const performAdminAction = async (action: "assign" | "reassign" | "unassign" | "forceResolve") => {
    if (actionPending) return
    setActionError(null)
    try {
      if (action === "assign" || action === "reassign") {
        if (!validAssignee) throw new Error(t("Wybierz agenta."))
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
      setActionError(error instanceof Error ? error : new Error(t("Nie udało się wykonać akcji administratora.")))
    }
  }

  return (
    <section className={cn(className, "conversation-panel min-h-0 min-w-0 flex-col bg-[#08111f]")} aria-label={t("Rozmowa {{value0}}", { value0: item.company })}>
      <header data-case-action-region className="shrink-0 border-b border-white/[0.08] bg-[#08111f] px-3 py-3 sm:px-5">
        <div className="flex min-w-0 items-center gap-2">
          <Button type="button" variant="ghost" size="icon-sm" onClick={onBack} className="shrink-0 md:hidden" aria-label={t("Wróć do listy")}><ArrowLeft /></Button>
          <div className="min-w-0 flex-1">
            <h2 ref={conversationTitleRef} tabIndex={-1} className="truncate text-lg font-bold tracking-[-0.02em]">{item.source}</h2>
            <div className="flex min-w-0 items-center gap-2 text-xs text-[#cbd3df]">
              {item.company !== item.source && <span className="truncate">{item.company}</span>}
              <span className="truncate">{item.platform} · {item.reference}</span>
            </div>
          </div>
          <StatusBadge tone={item.statusTone}>{item.status}</StatusBadge>
          <Button ref={detailsToggleRef} type="button" variant="outline" size="icon-sm" onClick={onToggleDetails}
            className="shrink-0 border-white/10 bg-[#111a28]" aria-label={t("Szczegóły sprawy")}
            aria-expanded={detailsOpen} aria-controls={detailsOpen ? "case-details" : undefined}><PanelRight /></Button>
        </div>
          <div className="mt-3 flex flex-wrap items-center gap-1.5">
            <button type="button" onClick={() => workflow.claim.mutate()} disabled={!canClaim || workflow.claim.isPending} aria-busy={workflow.claim.isPending} className="flex h-8 items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-2.5 text-[11px] text-[#d8dce4] disabled:cursor-not-allowed disabled:opacity-50"><UserRound className="size-[17px]" />{workflow.claim.isPending ? t("Przejmowanie…") : t("Przejmij")}</button>
            <button type="button" onClick={() => record?.snoozedForCurrentUserUntil ? workflow.cancelSnooze.mutate() : setActionDialog("snooze")} disabled={workflow.cancelSnooze.isPending || (!record?.snoozedForCurrentUserUntil && !canSnooze)} className="flex h-8 items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-2.5 text-[11px] text-[#d8dce4] disabled:opacity-50"><AlarmClock className="size-[17px]" />{record?.snoozedForCurrentUserUntil ? t("Anuluj odłożenie") : t("Odłóż")}</button>
            <button type="button" onClick={() => setActionDialog("ignore")} disabled={!canIgnore} className="flex h-8 items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-2.5 text-[11px] text-[#d8dce4] disabled:opacity-50"><ThumbsDown className="size-[17px]" /> {t("Ignoruj")} {record ? `${record.ignoreVotes.current}/2` : ""}</button>
            <button type="button" onClick={() => setActionDialog("ask")} disabled={!canAsk} className="flex h-8 items-center rounded-[9px] border border-white/[0.07] bg-[#111a28] px-2.5 text-[11px] text-[#d8dce4] disabled:opacity-50">{t("Dopytaj")}</button>
            <button type="button" onClick={() => setActionDialog("resolve")} disabled={!canResolve} className="flex h-8 items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-2.5 text-[11px] text-[#d8dce4] disabled:opacity-50"><Check className="size-[17px]" /> {t("Rozwiąż")}</button>
            {canReopen && <button type="button" onClick={() => setActionDialog("reopen")}
              className="flex h-8 items-center rounded-[9px] border border-violet-500/20 bg-violet-950/30 px-2.5 text-[11px] text-violet-200">
              <RotateCcw className="mr-2 size-3.5" />{t("Cofnij rozwiązanie")}
            </button>}
            {canAdmin && <button type="button" onClick={() => setActionDialog("admin")} className="flex h-8 items-center rounded-[9px] border border-violet-500/20 bg-violet-950/30 px-2.5 text-[11px] text-violet-200">{t("Administruj")}</button>}
          </div>
        {record?.snoozedForCurrentUserUntil && <p className="mt-2 text-xs text-[#cbd3df]" data-active-snooze>
          <SnoozeTime until={record.snoozedForCurrentUserUntil} from={record.snoozedForCurrentUserAt} />
        </p>}
        {detailError && <p role="alert" className="text-xs text-red-400">{t("Nie udało się wczytać szczegółów case’u.")}</p>}
        {workflow.claim.isError && <p role="alert" className="text-xs text-red-400">{userError(workflow.claim.error)}</p>}
        {(workflow.cancelSnooze.isError || workflow.retryMessage.isError) && <p role="alert" className="text-xs text-red-400">{t("Nie udało się odświeżyć stanu sprawy.")}</p>}
      </header>

      <div className="cases-scrollbar min-h-0 min-w-0 flex-1 overflow-y-auto bg-[radial-gradient(circle_at_72%_34%,rgba(23,48,76,0.12),transparent_42%)] px-3 py-4 sm:px-5">
        {hasOlderMessages && <button type="button" onClick={loadOlderMessages} className="mb-5 text-sm text-violet-300">{t("Wczytaj starsze wiadomości")}</button>}
        {messagesLoading ? <p className="text-sm text-[#9aa5b6]">{t("Wczytywanie wiadomości…")}</p> : messagesError ? <p role="alert" className="text-sm text-red-400">{t("Nie udało się wczytać wiadomości.")}</p> : messages.length === 0 ? <p className="text-sm text-[#9aa5b6]">{t("Brak wiadomości.")}</p> : (
          <div className="flex w-full max-w-[820px] flex-col gap-5">
            {messages.map((message) => {
              if (message.kind === "internal_note") {
                return (
                  <article key={message.id} data-message-kind="internal_note" className="min-w-0 rounded-lg border border-amber-400/50 bg-amber-400/15 px-3 py-2 [overflow-wrap:anywhere]">
                    <div className="flex flex-wrap items-baseline gap-x-2 text-xs text-amber-100">
                      <span className="font-semibold text-amber-300">{t("Notatka wewnętrzna")}</span>
                      <span data-message-author>{message.sender}</span>
                      <time dateTime={message.createdAt}>{timeLabel(message.createdAt)}</time>
                    </div>
                    <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-amber-50">{message.body}</p>
                  </article>
                )
              }
              const sender = message.sender || t(message.kind === "support" ? "Wsparcie" : message.kind === "customer" ? "Klient" : "System")
              return (
                <article key={message.id} data-message-kind={message.kind} data-message-deleted={message.deleted || undefined} aria-label={message.deleted ? t("Usunięta wiadomość") : undefined} className={cn("flex min-w-0 items-start gap-2.5", message.deleted && "grayscale")}>
                  <Avatar initials={initials(sender)} size="sm" />
                  <div className={cn("min-w-0 flex-1 rounded-lg px-3 py-2 [overflow-wrap:anywhere]",
                    message.deleted ? "border border-white/10 bg-white/[0.02]" : message.kind === "support" ? "border border-violet-400/15 bg-violet-500/10" : "border border-white/[0.05] bg-white/[0.025]")}>
                    <div className="flex flex-wrap items-baseline gap-x-2 text-xs text-[#aeb8c8]">
                      <span className="font-semibold text-[#e1e5ed]" data-message-author>{sender}</span>
                      <time dateTime={message.createdAt}>{timeLabel(message.createdAt)}</time>
                      {message.edited && <span>{t("Edytowano")}</span>}
                      {message.deliveryStatus && <span>{t(deliveryStatusLabels[message.deliveryStatus])}</span>}
                    </div>
                    {message.deleted && <p className="mt-1 text-xs italic text-[#aeb8c8]">{t("Wiadomość została usunięta.")}</p>}
                    {message.recoverySource && <p className="mt-1 text-xs font-medium text-orange-400" title={message.addedAt ? `${t("Dodano do Application Support")}: ${new Date(message.addedAt).toLocaleString()}` : undefined}>{t("Pominięta wiadomość · odzyskana przez resync")}</p>}
                    {message.body && <p className={cn("mt-1 whitespace-pre-wrap text-sm leading-6", message.deleted ? "text-[#aeb8c8]" : "text-[#edf0f4]")}>
                      <SafeExternalMessage slack={message.kind === "customer" && item.platform === "Slack"} content={message.body} />
                    </p>}
                    <MessageAttachments attachments={message.attachments} canManageIntegrations={canManageIntegrations} />
                    <DeliveryNotice message={message} canManageIntegrations={canManageIntegrations} />
                    {message.retryAllowed && <button type="button" onClick={() => workflow.retryMessage.mutate(message.id)} disabled={workflow.retryMessage.isPending} className="mt-2 inline-flex items-center gap-1.5 rounded-md border border-red-300/20 px-2.5 py-1.5 text-xs text-red-200 disabled:opacity-50"><RotateCcw className="size-3.5" /> {t("Ponów wysyłkę")}</button>}
                  </div>
                </article>
              )
            })}
          </div>
        )}
      </div>

      <div className="shrink-0 px-3 pb-3 pt-1 sm:px-5">
        <div className={cn("max-w-[820px] rounded-[11px] border bg-[linear-gradient(110deg,#0d1725,#0b1522)]",
          noteMode ? "border-amber-400/50" : "border-white/[0.085]")}>
          <div role="group" aria-label={t("Tryb edytora")} className="flex flex-wrap gap-1.5 border-b border-white/10 px-3 py-2">
            <Button type="button" size="sm" variant="ghost" aria-pressed={!noteMode} disabled={submitting}
              className="aria-pressed:bg-violet-500/20" onClick={() => setNoteMode(false)}>{t("Odpowiedź publiczna")}</Button>
            <Button type="button" size="sm" variant="ghost" aria-pressed={noteMode} disabled={submitting || !canReply}
              className="text-amber-200 aria-pressed:bg-amber-400/20" onClick={() => setNoteMode(true)}>{t("Notatka wewnętrzna")}</Button>
          </div>
          <textarea aria-describedby="composer-help" onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }} onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing && !composing.current && event.nativeEvent.keyCode !== 229) {
              event.preventDefault()
              if (!event.repeat) void send()
            }
          }} onPaste={(event) => {
            if (noteMode) return
            const clipboard = event.clipboardData
            const pasted = clipboard.files.length > 0 ? Array.from(clipboard.files) : Array.from(clipboard.items)
              .filter((item) => item.kind === "file")
              .flatMap((item) => item.getAsFile() ?? [])
            const images = pasted
              .filter((file) => file.type.startsWith("image/"))
              .map((file) => {
                if (file.name) return file
                const extension = file.type.split("/")[1] === "jpeg" ? "jpg" : file.type.split("/")[1]
                return new File([file], `${t("Zrzut ekranu")}.${extension}`, { type: file.type })
              })
            if (!images.length) return
            if (!event.clipboardData.types.includes("text/plain")) event.preventDefault()
            addFiles(images)
          }} value={noteMode ? noteDraft : draft} maxLength={noteMode ? 10000 : undefined} onChange={(event) => {
            if (noteMode) {
              setNoteDraft(event.target.value)
              noteKey.current = null
            } else {
              setDraft(event.target.value)
              submission.current = null
            }
          }} disabled={!canReply || submitting} placeholder={canReply ? t(noteMode ? "Napisz notatkę..." : "Napisz odpowiedź...") : t("Przejmij case, aby odpowiedzieć")} aria-label={t(noteMode ? "Treść notatki" : "Treść odpowiedzi")} className="block h-[80px] w-full resize-none bg-transparent px-[23px] pt-[17px] text-[13px] text-[#eef0f4] outline-none placeholder:text-[#8e99aa] disabled:cursor-not-allowed" />
          {!noteMode && files.length > 0 && <div className="flex flex-wrap gap-2 px-[19px] pb-2">{files.map((file, index) => <button key={`${file.name}-${file.size}-${index}`} type="button" disabled={workflow.sendMessage.isPending} onClick={() => { setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index)); submission.current = null; setSendError(null) }} className="flex min-w-0 max-w-full items-center gap-2 rounded-md bg-white/[0.06] px-2 py-1 text-left text-xs text-[#cbd3df] [overflow-wrap:anywhere]" title={t("Usuń plik")}><AttachmentPreview file={file} /><span>{file.name}</span></button>)}</div>}
          <div className="flex min-h-[51px] items-center justify-between gap-2 px-[19px]">
            {noteMode ? <span className="text-xs text-amber-200">{t("Widoczna tylko w USI")}</span> : <label className={cn("inline-flex cursor-pointer items-center gap-2 rounded text-xs text-[#aeb8c8] focus-within:ring-2 focus-within:ring-violet-400", !canReply && "pointer-events-none opacity-50")}><Paperclip className="size-4" /> {t("Dodaj pliki")}<input type="file" multiple disabled={!canReply || submitting} className="sr-only" onChange={(event) => { addFiles(Array.from(event.target.files ?? [])); event.target.value = "" }} /></label>}
            <button type="button" data-case-action aria-busy={submitting} onClick={send} disabled={!canReply || !hasContent || submitting}
              className={cn("h-[42px] shrink-0 rounded-[9px] px-4 text-[12px] font-medium disabled:cursor-not-allowed disabled:opacity-50",
                noteMode ? "bg-amber-300 text-amber-950" : "bg-[linear-gradient(135deg,#5b23e5,#4c17c9)] text-white")}>
              {noteMode ? t(submitting ? "Zapisywanie…" : "Zapisz notatkę") : t(submitting ? "Wysyłanie…" : "Wyślij")}
            </button>
          </div>
        </div>
        <p id="composer-help" className="mt-1 text-[11px] text-[#9aa5b6]">{t(noteMode ? "Enter zapisuje · Shift+Enter dodaje nowy wiersz" : "Enter wysyła · Shift+Enter dodaje nowy wiersz")}</p>
        {(noteMode ? noteError : sendError) && <p role="alert" className="mt-2 text-sm text-red-400">{userError(noteMode ? noteError : sendError)}</p>}
      </div>
      {actionDialog && <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4" role="dialog" aria-modal="true"><div className="w-full max-w-md rounded-xl border border-white/10 bg-[#111b29] p-5 shadow-2xl"><h3 className="text-lg font-semibold text-white">{actionDialog === "ask" ? t("Dopytaj klienta") : actionDialog === "ignore" ? t("Głos ignorowania") : actionDialog === "resolve" ? t("Rozwiąż sprawę") : actionDialog === "snooze" ? t("Odłóż sprawę") : actionDialog === "reopen" ? t("Cofnij rozwiązanie") : t("Administracja sprawą")}</h3>
        {actionDialog === "reopen" && <p className="mt-3 text-sm text-[#cbd3df]">{t("Sprawa wróci do kolejki. Jeśli ten wątek ma już aktywną sprawę, otworzy się ona.")}</p>}
        {actionDialog === "ask" && <><textarea autoFocus value={actionText} onChange={(event) => setActionText(event.target.value)} className="mt-4 h-28 w-full rounded-lg border border-white/10 bg-[#08111f] p-3 text-sm" placeholder={t("Pytanie do klienta")} /><label className="mt-3 block text-xs text-[#aeb8c8]">{t("Czas oczekiwania")}<select value={waitingMinutes} onChange={(event) => setWaitingMinutes(Number(event.target.value))} className="mt-1 w-full rounded-lg border border-white/10 bg-[#08111f] p-2"><option value={60}>{t("1 godzina")}</option><option value={24 * 60}>{t("24 godziny")}</option><option value={3 * 24 * 60}>{t("3 dni")}</option><option value={7 * 24 * 60}>{t("7 dni")}</option></select></label></>}
        {actionDialog === "ignore" && <textarea autoFocus value={actionText} onChange={(event) => setActionText(event.target.value)} className="mt-4 h-24 w-full rounded-lg border border-white/10 bg-[#08111f] p-3 text-sm" placeholder={t("Powód (opcjonalnie)")} />}
        {(actionDialog === "resolve" || actionDialog === "admin") && <label className="mt-4 block text-xs text-[#aeb8c8]">{t("Kategoria rozwiązania")}<select value={resolutionCategory} onChange={(event) => setResolutionCategory(event.target.value)} className="mt-1 w-full rounded-lg border border-white/10 bg-[#08111f] p-2"><option value="">{t("Bez kategorii")}</option><option value="SOLVED">{t("Rozwiązane")}</option><option value="NO_ACTION_REQUIRED">{t("Brak wymaganej akcji")}</option><option value="DUPLICATE">{t("Duplikat")}</option><option value="OTHER">{t("Inne")}</option></select></label>}
        {actionDialog === "snooze" && <><div className="mt-4 flex gap-2">{[["1h", 60], [t("Jutro"), 24 * 60], [t("Tydzień"), 7 * 24 * 60]].map(([label, minutes]) => <button key={String(label)} type="button" onClick={() => setSnoozeUntil(localDateTime(new Date(Date.now() + Number(minutes) * 60_000)))} className="rounded-md border border-white/10 px-3 py-1.5 text-xs">{label}</button>)}</div><input aria-label={t("Data i godzina zakończenia odłożenia")} type="datetime-local" value={snoozeUntil} onChange={(event) => setSnoozeUntil(event.target.value)} className="mt-3 w-full rounded-lg border border-white/10 bg-[#08111f] p-2 text-sm" /></>}
        {actionDialog === "admin" && <label className="mt-4 block text-xs text-[#aeb8c8]">{t("Agent")}<select value={validAssignee ? assigneeId : ""} onChange={(event) => setAssigneeId(event.target.value)} className="mt-1 w-full rounded-lg border border-white/10 bg-[#08111f] p-2"><option value="">{t("Wybierz agenta")}</option>{availableAssignees.map((agent) => <option key={agent.id} value={agent.id}>{agent.displayName}{agent.ignoreWeight === 2 ? t(" · głos ignorowania ×2") : ""}</option>)}</select></label>}
        {actionError && <p role="alert" className="mt-3 text-sm text-red-400">{userError(actionError)}</p>}
        <div data-case-action-region aria-busy={actionPending} className="mt-5 flex flex-wrap justify-end gap-2"><button type="button" onClick={() => { setActionDialog(null); setActionError(null); workflowActionKey.current = null }} disabled={actionPending} className="rounded-lg border border-white/10 px-4 py-2 text-sm disabled:opacity-50">{t("Anuluj")}</button>{actionDialog === "admin" ? <>{record?.availableActions?.includes("ASSIGN") && <button type="button" onClick={() => void performAdminAction("assign")} disabled={actionPending || !validAssignee} className="rounded-lg bg-violet-700 px-4 py-2 text-sm disabled:opacity-50">{t("Przypisz")}</button>}{record?.availableActions?.includes("REASSIGN") && <button type="button" onClick={() => void performAdminAction("reassign")} disabled={actionPending || !validAssignee} className="rounded-lg bg-violet-700 px-4 py-2 text-sm disabled:opacity-50">{t("Przypisz ponownie")}</button>}{record?.availableActions?.includes("UNASSIGN") && <button type="button" onClick={() => void performAdminAction("unassign")} disabled={actionPending} className="rounded-lg border border-amber-400/30 px-4 py-2 text-sm text-amber-200 disabled:opacity-50">{t("Cofnij przypisanie")}</button>}{record?.availableActions?.includes("FORCE_RESOLVE") && <button type="button" onClick={() => void performAdminAction("forceResolve")} disabled={actionPending} className="rounded-lg bg-red-700 px-4 py-2 text-sm disabled:opacity-50">{t("Wymuś rozwiązanie")}</button>}</> : <button type="button" onClick={() => void performAction()} disabled={actionPending || (actionDialog === "ask" && !actionText.trim())} className="rounded-lg bg-violet-700 px-4 py-2 text-sm disabled:opacity-50">{actionPending ? t("Zapisywanie…") : actionDialog === "reopen" ? t("Cofnij rozwiązanie") : t("Zatwierdź")}</button>}</div>
      </div></div>}
    </section>
  )
}

function CaseDetails({ item, record, activity, items }: {
  item: CasePresentation
  record?: InboxCase
  activity: ReturnType<typeof useInboxActivity>
  items: InboxActivity[]
}) {
  const t = useT()
  const owner = record ? record.owner?.fullName : item.unassigned ? undefined : item.owner
  return (
    <div className="space-y-5 px-4 py-4 text-sm">
      <dl className="space-y-3 rounded-lg border border-white/[0.08] bg-[#111b29] p-3 [overflow-wrap:anywhere]">
        {record && <div>
          <dt className="text-xs text-[#aeb8c8]">{t("Utworzono")}</dt>
          <dd className="mt-1"><time dateTime={record.createdAt}>{timeLabel(record.createdAt)}</time></dd>
        </div>}
        <div>
          <dt className="text-xs text-[#aeb8c8]">{t("Właściciel")}</dt>
          <dd className="mt-1 flex items-center gap-2">{owner && <Avatar initials={initials(owner)} size="xs" />}<span data-case-detail-owner>{owner || t("Nieprzypisane")}</span></dd>
        </div>
        <div>
          <dt className="sr-only">SLA</dt>
          <dd><SlaBadge tone={item.slaTone} title={item.slaTitle}>{item.sla}</SlaBadge>
            {record && (record.sla.previouslyBreached || (item.terminal && record.sla.state === "breached")) && <p className="mt-2 text-xs text-[#cbd3df]">{t("Historyczne przekroczenie SLA")}</p>}
          </dd>
        </div>
        {record?.closureReason === "IGNORED" && <div>
          <dt className="text-xs text-[#aeb8c8]">{t("Powód zamknięcia")}</dt>
          <dd className="mt-1">{t("Zignorowane")}</dd>
        </div>}
        {record && record.ignoreVotes.current > 0 && <div>
          <dt className="text-xs text-[#aeb8c8]">{t("Punkty ignorowania")}</dt>
          <dd className="mt-1">{record.ignoreVotes.current} / {record.ignoreVotes.required}</dd>
        </div>}
        {record?.integration && <div>
          <dt className="text-xs text-[#aeb8c8]">{t("Integracja")}</dt>
          <dd className="mt-1">{record.integration.name}{record.integration.workspace && <><br />{record.integration.workspace}</>}</dd>
          <dd className="mt-1 text-xs text-[#aeb8c8]">{item.source} · {record.integration.externalChannelId}</dd>
        </div>}
        {record?.relatedCase?.id && <div>
          <dt className="text-xs text-[#aeb8c8]">{t("Powiązany:")}</dt>
          <dd><a href={"/cases?caseId=" + encodeURIComponent(record.relatedCase.id)} className="text-violet-300">{record.relatedCase.reference}</a></dd>
        </div>}
      </dl>
      <details>
        <summary className="cursor-pointer font-medium text-[#d8dce4]">{t("Historia aktywności (")}{items.length})</summary>
        {activity.isLoading && <p className="mt-2 text-xs text-[#aeb8c8]">{t("Wczytywanie…")}</p>}
        {activity.isError && <p role="alert" className="mt-2 text-xs text-red-300">{t("Nie udało się wczytać historii aktywności.")}</p>}
        {items.length === 0 && activity.isSuccess && <p className="mt-2 text-xs text-[#aeb8c8]">{t("Brak aktywności.")}</p>}
        <ol className="mt-2 space-y-2 [overflow-wrap:anywhere]">
          {items.map((event) => <li key={event.id} className="rounded-lg border border-white/[0.06] bg-[#111b29] px-3 py-2 text-xs">
            <div><ActivityDescription event={event} /></div>
            <time dateTime={event.createdAt} className="mt-1 block text-[#aeb8c8]">{timeLabel(event.createdAt)}</time>
          </li>)}
        </ol>
        {activity.hasNextPage && <button type="button" onClick={() => activity.fetchNextPage()} className="mt-2 text-xs text-violet-300">{t("Wczytaj starsze")}</button>}
      </details>
    </div>
  )
}

function SnoozeTime({ until, from }: { until: string; from?: string }) {
  const t = useT()
  const minutes = from ? (Date.parse(until) - Date.parse(from)) / 60_000 : 0
  return <time dateTime={until}>
    {t("Odłożono do {{time}}", { time: timeLabel(until) })}
    {minutes > 0 && ` · ${formatDuration(Math.round(minutes))}`}
  </time>
}

function ActivityDescription({ event }: { event: InboxActivity }) {
  const t = useT()
  if (event.action === "CASE_CUSTOMER_REPLY") return <>{t("Klient odpowiedział")}</>
  if (event.action === "CASE_SNOOZE" && event.snoozedUntil) {
    return <SnoozeTime until={event.snoozedUntil} from={event.snoozedAt} />
  }
  return <>{t(event.label)} · {event.author ?? t("System")}{event.historicalSlaBreach && <> · {t("Historyczne przekroczenie SLA")}</>}{event.snoozedUntil && <> · <SnoozeTime until={event.snoozedUntil} from={event.snoozedAt} /></>}</>
}

function FilterSelect({ label, value, onChange, options }: { label: string; value: string; onChange: (value: string) => void; options: Array<{ value: string; label: string }> }) {
  const t = useT()
  return <label className="grid grid-cols-[88px_1fr] items-center gap-2 text-[11px] text-[#aeb8c8]"><span>{t(label)}</span><select aria-label={label} value={value} onChange={(event) => onChange(event.target.value)} className="min-w-0 rounded-md border border-white/10 bg-[#08111f] px-2 py-1.5 text-xs text-[#e8eaf0]"><option value="">{t("Wszystkie")}</option>{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
}

function localDateTime(value: Date): string {
  const offset = value.getTimezoneOffset() * 60_000
  return new Date(value.getTime() - offset).toISOString().slice(0, 16)
}

function DeliveryNotice({ message, canManageIntegrations }: { message: InboxMessage; canManageIntegrations: boolean }) {
  const t = useT()
  const presentation = deliveryPresentation(message.deliveryErrorCode)
  if (message.deliveryStatus === "queued" && message.nextRetryAt) {
    return (
      <p className="mt-2 text-xs text-amber-200">
        {t(presentation.retryMessage ?? "Zaplanowano automatyczne ponowienie.")}{" "}
        {t("Następna próba: {{time}}", { time: timeLabel(message.nextRetryAt) })}
      </p>
    )
  }
  if (message.deliveryStatus !== "failed") return null
  return (
    <div className="mt-2 text-xs text-red-200">
      <p>{t(presentation.message)}</p>
      {presentation.requiredScope && canManageIntegrations && (
        <p>{t("Brakujące uprawnienie Slacka: {{scope}}", { scope: presentation.requiredScope })}</p>
      )}
      {presentation.integration && canManageIntegrations && (
        <Link className="mt-1 inline-block underline" href="/settings?tab=integrations">{t("Otwórz ustawienia integracji")}</Link>
      )}
    </div>
  )
}

function AttachmentPreview({ file }: { file: File }) {
  const image = useRef<HTMLImageElement>(null)
  useEffect(() => {
    const element = image.current
    if (!element) return
    const preview = URL.createObjectURL(file)
    element.src = preview
    return () => URL.revokeObjectURL(preview)
  }, [file])
  if (!["image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp"].includes(file.type)) return null
  // Local object URLs need no Next.js image optimization.
  // eslint-disable-next-line @next/next/no-img-element
  return <img ref={image} alt="" className="size-8 shrink-0 rounded object-cover" />
}

function Avatar({ initials, size }: { initials: string; size: "sm" | "xs" }) {
  return (
    <span aria-hidden="true" className={cn("grid shrink-0 place-items-center rounded-full bg-violet-900/60 font-semibold text-violet-100",
      size === "sm" ? "size-8 text-xs" : "size-6 text-[10px]")}>{initials}</span>
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
