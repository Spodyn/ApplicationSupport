"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import {
  AlarmClock,
  ArrowLeft,
  Braces,
  Check,
  CheckCheck,
  ChevronDown,
  Clock3,
  ExternalLink,
  Info,
  Paperclip,
  Search,
  SlidersHorizontal,
  Smile,
  UserRound,
} from "lucide-react"
import type { InboxCase } from "@/lib/domain/inbox"
import { useInboxCases, useInboxMessages, useMarkInboxCaseRead } from "@/lib/services/queries"
import { cn } from "@/lib/utils"

type QuickFilter = "all" | "sla" | "mine"
type AdvancedFilter = "mine" | "unassigned" | "sla" | "unread"

type CasePresentation = {
  reference: string
  initials: string
  company: string
  subject: string
  time: string
  platform: "Slack" | "E-mail" | "Telegram"
  source: string
  status: string
  statusTone: "red" | "blue" | "purple" | "green"
  sla: string
  slaTone: "red" | "amber" | "green"
  unread?: boolean
  breached?: boolean
  mine?: boolean
}

const casePresentations: CasePresentation[] = [
  {
    reference: "ZG-2048",
    initials: "NR",
    company: "Northstar Retail",
    subject: "Płatność pobrana dwukrotnie po odnowieniu subskrypcji",
    time: "7 min temu",
    platform: "Slack",
    source: "#rozliczenia-premium",
    status: "W trakcie weryfikacji",
    statusTone: "red",
    sla: "SLA +15 min",
    slaTone: "red",
    breached: true,
  },
  {
    reference: "ZG-2051",
    initials: "EC",
    company: "Evergreen Cloud",
    subject: "Kanał alarmowy nie synchronizuje wiadomości od godziny.",
    time: "9 min temu",
    platform: "Slack",
    source: "#incydenty",
    status: "Oczekuje na klienta",
    statusTone: "blue",
    sla: "18 min do SLA",
    slaTone: "amber",
  },
  {
    reference: "ZG-2044",
    initials: "OL",
    company: "Orbit Labs",
    subject: "Brak możliwości logowania po aktywacji SSO",
    time: "11 min temu",
    platform: "E-mail",
    source: "#helpdesk",
    status: "Oczekuje na zespół",
    statusTone: "purple",
    sla: "42 min do SLA",
    slaTone: "green",
  },
  {
    reference: "ZG-2053",
    initials: "NW",
    company: "Nova Works",
    subject: "Duplikaty powiadomień po ponownym połączeniu workspace.",
    time: "13 min temu",
    platform: "Telegram",
    source: "@support",
    status: "Nowy",
    statusTone: "green",
    sla: "SLA +30 min",
    slaTone: "red",
    unread: true,
  },
  {
    reference: "ZG-2038",
    initials: "VE",
    company: "Vistala Energy",
    subject: "Nie mogę pobrać faktury VAT",
    time: "15 min temu",
    platform: "E-mail",
    source: "faktury@vistala.com",
    status: "Oczekuje na klienta",
    statusTone: "blue",
    sla: "1 godz. do SLA",
    slaTone: "green",
    unread: true,
  },
  {
    reference: "ZG-2029",
    initials: "AC",
    company: "Atlas Commerce",
    subject: "Czy można zintegrować z API v2?",
    time: "22 min temu",
    platform: "Slack",
    source: "#integracje",
    status: "Nowy",
    statusTone: "green",
    sla: "SLA +2 godz.",
    slaTone: "red",
    unread: true,
    mine: true,
  },
]

export function CasesPage({
  onlyMine = false,
  initialCaseId,
}: {
  onlyMine?: boolean
  initialCaseId?: string
}) {
  const casesQuery = useInboxCases()
  const markRead = useMarkInboxCaseRead()
  const [selectedReference, setSelectedReference] = useState(() => {
    if (!initialCaseId) return casePresentations[0].reference
    return casePresentations.find((item) => item.reference === initialCaseId)?.reference ?? casePresentations[0].reference
  })
  const [mobileConversationOpen, setMobileConversationOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [quickFilter, setQuickFilter] = useState<QuickFilter>(onlyMine ? "mine" : "all")
  const [filterMenuOpen, setFilterMenuOpen] = useState(false)
  const [advancedFilters, setAdvancedFilters] = useState<AdvancedFilter[]>([])
  const [locallyRead, setLocallyRead] = useState<string[]>([])
  const filterMenuRef = useRef<HTMLDivElement>(null)

  const recordsByReference = useMemo(
    () => new Map((casesQuery.data ?? []).map((item) => [item.reference, item])),
    [casesQuery.data],
  )

  const visibleCases = useMemo(() => {
    const normalized = search.trim().toLocaleLowerCase("pl")
    return casePresentations.filter((item) => {
      if (normalized && !`${item.company} ${item.subject} ${item.source}`.toLocaleLowerCase("pl").includes(normalized)) return false
      if (quickFilter === "sla" && !item.breached) return false
      if (quickFilter === "mine" && !item.mine) return false
      if (advancedFilters.includes("mine") && !item.mine) return false
      if (advancedFilters.includes("sla") && item.slaTone !== "red") return false
      if (advancedFilters.includes("unread") && (!item.unread || locallyRead.includes(item.reference))) return false
      if (advancedFilters.includes("unassigned") && recordsByReference.get(item.reference)?.owner) return false
      return true
    })
  }, [advancedFilters, locallyRead, quickFilter, recordsByReference, search])

  const selectedPresentation = casePresentations.find((item) => item.reference === selectedReference) ?? casePresentations[0]
  const selectedRecord = recordsByReference.get(selectedPresentation.reference)
  useInboxMessages(selectedRecord?.id)

  useEffect(() => {
    const handlePointerDown = (event: PointerEvent) => {
      if (!filterMenuRef.current?.contains(event.target as Node)) setFilterMenuOpen(false)
    }
    document.addEventListener("pointerdown", handlePointerDown)
    return () => document.removeEventListener("pointerdown", handlePointerDown)
  }, [])

  const selectCase = (item: CasePresentation) => {
    setSelectedReference(item.reference)
    setMobileConversationOpen(true)
    if (item.unread && !locallyRead.includes(item.reference)) {
      setLocallyRead((current) => [...current, item.reference])
      const record = recordsByReference.get(item.reference)
      if (record) markRead.mutate(record.id)
    }
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
            <span className="rounded-full bg-[#151f2e] px-2 py-0.5 text-[12px] font-semibold text-[#d8dce4]">18</span>
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
                key={item.reference}
                item={item}
                selected={item.reference === selectedReference}
                unread={Boolean(item.unread && !locallyRead.includes(item.reference))}
                onSelect={() => selectCase(item)}
              />
            ))
          ) : (
            <div className="mt-10 px-5 text-center text-sm text-[#9aa5b6]">Brak rozmów pasujących do filtrów.</div>
          )}
        </div>
      </section>

      <ConversationPanel
        item={selectedPresentation}
        record={selectedRecord}
        onBack={() => setMobileConversationOpen(false)}
        className={mobileConversationOpen ? "flex" : "hidden lg:flex"}
      />
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
  item,
  record,
  onBack,
  className,
}: {
  item: CasePresentation
  record?: InboxCase
  onBack: () => void
  className: string
}) {
  const [draft, setDraft] = useState("")
  const [alsoOnChannel, setAlsoOnChannel] = useState(false)
  const [replyingTo, setReplyingTo] = useState<string | null>(null)

  return (
    <section className={cn(className, "min-h-0 min-w-0 flex-col bg-[#08111f]")} aria-label={`Rozmowa ${item.company}`}>
      <header className="h-[148px] shrink-0 border-b border-white/[0.08] bg-[#08111f] px-[23px] py-[19px]">
        <div className="flex min-w-0 items-start gap-3">
          <button type="button" onClick={onBack} className="mt-1 grid size-8 shrink-0 place-items-center rounded-lg text-[#9ba6b8] hover:bg-white/5 lg:hidden" aria-label="Wróć do listy">
            <ArrowLeft className="size-5" />
          </button>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <h2 className="truncate text-[22px] font-bold leading-7 tracking-[-0.02em]">{item.source}</h2>
              <ExternalLink className="size-[15px] text-[#8f9caf]" />
            </div>
            <div className="mt-[3px] flex items-center gap-2 text-[13px] text-[#d7dbe3]">
              <span>{item.company}</span><span className="size-1 rounded-full bg-[#697587]" />
              <a href="#source" onClick={(event) => event.preventDefault()} className="flex items-center gap-1.5 font-medium text-[#e0e3e9] hover:text-violet-300">
                <SlackMark /> Slack <ExternalLink className="size-3.5 text-[#8592a5]" />
              </a>
            </div>
          </div>
          <HeaderActions />
        </div>

        <div className="mt-[15px] flex items-center gap-3 pl-0 lg:pl-0">
          <button type="button" className="flex h-[45px] items-center gap-2 rounded-[9px] border border-violet-500/[0.12] bg-violet-950/35 px-3.5 text-[12px] font-medium text-violet-300 hover:bg-violet-950/50">
            {item.status} <ChevronDown className="size-3.5" />
          </button>
          <button type="button" className="flex h-[45px] items-center gap-2.5 rounded-[9px] border border-white/[0.09] bg-[#0d1624] px-3.5 text-[12px] text-[#edf0f4] hover:border-white/[0.16]">
            <Avatar initials="MW" size="sm" online />
            Magdalena Wiśniewska <ChevronDown className="size-3.5 text-[#8995a7]" />
          </button>
          <div className="flex h-[45px] items-center gap-2.5 rounded-[9px] border border-red-500/[0.14] bg-red-950/20 px-3.5 text-[12px] font-semibold text-red-400">
            <Clock3 className="size-[17px]" /> SLA +15 min <Info className="size-[15px]" />
          </div>
        </div>
      </header>

      <ConversationBody
        company={item.company}
        isNorthstar={item.reference === "ZG-2048"}
        replyingTo={replyingTo}
        onReply={setReplyingTo}
      />

      <Composer
        value={draft}
        onChange={setDraft}
        alsoOnChannel={alsoOnChannel}
        onToggleChannel={() => setAlsoOnChannel((value) => !value)}
        replyingTo={replyingTo}
        onCancelReply={() => setReplyingTo(null)}
        owner={record?.owner?.fullName}
      />
    </section>
  )
}

function HeaderActions() {
  return (
    <div className="hidden shrink-0 items-center gap-3 xl:flex">
      <button disabled className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#7f899a] opacity-75 disabled:cursor-not-allowed"><UserRound className="size-[17px]" /> Przejmij</button>
      <button disabled className="flex h-[42px] items-center gap-2 rounded-[9px] border border-white/[0.07] bg-[#111a28] px-4 text-[12px] text-[#7f899a] opacity-75 disabled:cursor-not-allowed"><AlarmClock className="size-[17px]" /> Odłóż</button>
      <div className="flex h-[42px] overflow-hidden rounded-[9px] border border-white/[0.07] bg-[#111a28] text-[#7f899a] opacity-75">
        <button disabled className="flex items-center gap-2 px-4 text-[12px] disabled:cursor-not-allowed"><Check className="size-[17px]" /> Zamknij sprawę</button>
        <button disabled className="grid w-11 place-items-center border-l border-white/[0.07] disabled:cursor-not-allowed" aria-label="Opcje zamknięcia"><ChevronDown className="size-[16px]" /></button>
      </div>
    </div>
  )
}

function ConversationBody({
  company,
  isNorthstar,
  replyingTo,
  onReply,
}: {
  company: string
  isNorthstar: boolean
  replyingTo: string | null
  onReply: (message: string) => void
}) {
  return (
    <div className="cases-scrollbar min-h-0 flex-1 overflow-y-auto bg-[radial-gradient(circle_at_72%_34%,rgba(23,48,76,0.12),transparent_42%)] py-[22px] pl-[22px] pr-[12px]">
      <div className="flex min-h-full flex-col">
        <div className="flex items-start gap-[25px]">
          <Avatar initials="JB" />
          <div className="min-w-0 max-w-[570px]">
            <MessageAuthor name="Joanna Borkowska" time="16:38" />
            <p className="mt-2 text-[14px] leading-[25px] text-[#edf0f4]">
              {isNorthstar ? (
                <>W panelu widzę dwa obciążenia za ten sam okres rozliczeniowy.<br />Problem udaje się odtworzyć na dwóch kontach. Wysyłam<br className="hidden 2xl:block" /> dodatkowe szczegóły.</>
              ) : (
                <>Dzień dobry, potrzebujemy pomocy w sprawie zgłoszenia dla {company}.<br />Problem udało się odtworzyć na dwóch kontach.</>
              )}
            </p>
            {isNorthstar && <CodeBlock />}
          </div>
          <ReplyButton onClick={() => onReply("W panelu widzę dwa obciążenia...")} active={replyingTo !== null} />
          <span className="ml-auto pt-8 text-[12px] tabular-nums text-[#9aa5b7]">16:38</span>
        </div>

        <div className="mt-[-54px] flex justify-end">
          <AgentBubble time="18:31">Zweryfikowałam dane po naszej stronie. Zespół techniczny<br className="hidden 2xl:block" /> analizuje teraz konkretny request.</AgentBubble>
        </div>

        <div className="my-[13px] flex justify-center">
          <div className="rounded-[9px] border border-white/[0.055] bg-[#0d1725] px-3 py-2 text-[11px] text-[#9ba6b6]">
            <span className="mr-3 tabular-nums">12:38</span> Status SLA został ponownie przeliczony: <span className="ml-1 font-semibold text-red-400">SLA +15 min</span>
          </div>
        </div>

        <div className="flex items-start gap-[25px]">
          <Avatar initials="JB" />
          <div className="min-w-0">
            <MessageAuthor name="Joanna Borkowska" time="14:05" />
            <p className="mt-2 text-[14px] leading-6 text-[#edf0f4]">W panelu widzę dwa obciążenia za ten sam okres rozliczeniowy.</p>
          </div>
          <ReplyButton onClick={() => onReply("W panelu widzę dwa obciążenia...")} />
        </div>

        <div className="mt-[-1px] flex justify-end">
          <AgentBubble time="14:05">Dziękuję za zgłoszenie. Sprawdzam konfigurację oraz<br className="hidden 2xl:block" /> ostatnie zdarzenie integracji.</AgentBubble>
        </div>

        <div className="mt-0 flex items-start gap-[25px]">
          <Avatar initials="JB" />
          <div className="min-w-0">
            <MessageAuthor name="Joanna Borkowska" time="14:05" />
            <p className="mt-2 text-[14px] leading-6 text-[#edf0f4]">Dziękuję za aktualizację, czekam na dalsze informacje.</p>
          </div>
        </div>
      </div>
    </div>
  )
}

function MessageAuthor({ name, time }: { name: string; time: string }) {
  return <div className="flex items-center gap-3"><span className="text-[13px] font-medium text-[#f4f4f5]">{name}</span><span className="text-[11px] tabular-nums text-[#8f9bad]">{time}</span></div>
}

function ReplyButton({ onClick, active = false }: { onClick: () => void; active?: boolean }) {
  return <button type="button" onClick={onClick} className={cn("mt-7 grid size-[38px] shrink-0 place-items-center rounded-[9px] border border-white/[0.07] bg-[#0d1725] text-[#8996a8] hover:text-white", active && "text-violet-300")} aria-label="Odpowiedz na wiadomość"><ArrowLeft className="size-[17px] rotate-[25deg]" /></button>
}

function CodeBlock() {
  return (
    <div className="mt-3 w-[466px] max-w-full overflow-hidden rounded-[10px] border border-white/[0.09] bg-[#08121f] text-[12px]">
      <div className="flex h-[39px] items-center justify-between border-b border-white/[0.08] px-4 font-medium"><span>JSON</span><button type="button" className="text-[11px] font-semibold hover:text-violet-300">Kopiuj</button></div>
      <pre className="overflow-x-auto px-4 py-2 font-mono text-[12px] leading-[23px] text-[#d8dee9]">{`{
  "requestId": `}<span className="text-cyan-400">"ron_78_2949"</span>{`,
  "status": `}<span className="text-cyan-400">503</span>{`,
  "message": `}<span className="text-fuchsia-400">"upstream temporarily unavailable"</span>{`
}`}</pre>
    </div>
  )
}

function AgentBubble({ time, children }: { time: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-[18px]">
      <span className="text-[12px] tabular-nums text-[#8f9bad]">{time}</span>
      <div className="relative h-[98px] w-[416px] max-w-[46vw] overflow-hidden rounded-[12px] bg-[linear-gradient(135deg,rgba(52,28,104,0.86),rgba(32,24,73,0.9))] px-[14px] py-[10px] shadow-[inset_0_1px_0_rgba(255,255,255,0.025)]">
        <div className="flex items-center gap-2.5"><Avatar initials="MW" size="xs" /><span className="text-[11px] text-[#d7cfee]">Magdalena Wiśniewska</span><span className="ml-auto text-[10px] text-[#9e91bd]">{time}</span></div>
        <p className="mt-1.5 text-[12px] leading-[20px] text-[#ded9eb]">{children}</p>
        <div className="absolute bottom-2 right-3 text-violet-400"><CheckCheck className="size-[16px]" /></div>
      </div>
    </div>
  )
}

function Composer({
  value,
  onChange,
  alsoOnChannel,
  onToggleChannel,
  replyingTo,
  onCancelReply,
}: {
  value: string
  onChange: (value: string) => void
  alsoOnChannel: boolean
  onToggleChannel: () => void
  replyingTo: string | null
  onCancelReply: () => void
  owner?: string
}) {
  return (
    <div className="shrink-0 pb-[22px] pl-[18px] pr-[22px] pt-0">
      <div className="min-h-[118px] rounded-[11px] border border-white/[0.085] bg-[linear-gradient(110deg,#0d1725,#0b1522)] shadow-[0_8px_30px_rgba(0,0,0,0.13)]">
        {replyingTo && (
          <div className="flex items-center gap-2 border-b border-white/[0.06] px-4 py-2 text-[11px] text-[#9ba6b6]">
            <ArrowLeft className="size-3.5 rotate-[25deg] text-violet-300" /><span className="truncate">Odpowiedź: {replyingTo}</span><button type="button" className="ml-auto hover:text-white" onClick={onCancelReply}>×</button>
          </div>
        )}
        <textarea
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder="Napisz odpowiedź..."
          className="block h-[57px] w-full resize-none bg-transparent px-[23px] pt-[17px] text-[13px] text-[#eef0f4] outline-none placeholder:text-[#8e99aa]"
        />
        <div className="flex h-[51px] items-center px-[19px]">
          <div className="flex items-center gap-[21px] text-[#a7b1c0]">
            <ComposerIcon label="Dodaj załącznik"><Paperclip /></ComposerIcon>
            <ComposerIcon label="Dodaj emoji"><Smile /></ComposerIcon>
            <ComposerIcon label="Wstaw kod"><Braces /></ComposerIcon>
            <ComposerIcon label="Formatowanie"><span className="text-[15px] font-medium">Aa</span></ComposerIcon>
          </div>
          <div className="ml-auto flex items-center gap-3">
            <button type="button" onClick={onToggleChannel} className="hidden items-center gap-2 text-[12px] text-[#d6dae2] hover:text-white xl:flex">
              <span className={cn("grid size-[18px] place-items-center rounded-[3px] border", alsoOnChannel ? "border-violet-500 bg-violet-600" : "border-[#738095]")}>{alsoOnChannel && <Check className="size-3" />}</span>
              Wyślij również na kanał <Info className="ml-1 size-[15px] text-[#93a0b3]" />
            </button>
            <div className="flex h-[45px] overflow-hidden rounded-[9px] bg-[linear-gradient(135deg,#5b23e5,#4c17c9)] text-white shadow-[0_4px_18px_rgba(91,33,232,0.25)]">
              <button type="button" className="w-[117px] text-[12px] font-medium hover:bg-white/[0.06]">Wyślij</button>
              <button type="button" className="grid w-[50px] place-items-center border-l border-white/20 hover:bg-white/[0.06]" aria-label="Opcje wysyłania"><ChevronDown className="size-[16px]" /></button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function ComposerIcon({ label, children }: { label: string; children: React.ReactNode }) {
  return <button type="button" aria-label={label} title={label} className="grid size-5 place-items-center hover:text-white [&_svg]:size-[19px] [&_svg]:stroke-[1.7]">{children}</button>
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
