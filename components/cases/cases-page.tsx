"use client"

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react"
import {
  Activity,
  AlarmClock,
  Archive,
  ArrowLeft,
  Ban,
  Check,
  CheckCheck,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CircleCheckBig,
  CircleDot,
  Clock3,
  ExternalLink,
  FileArchive,
  FileText,
  ImageIcon,
  Inbox,
  Link2,
  LoaderCircle,
  LockKeyhole,
  Mail,
  MessageCircleQuestion,
  MessageSquareMore,
  MoreHorizontal,
  Paperclip,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  RotateCcw,
  Send,
  Smile,
  Bold,
  TriangleAlert,
  UserCheck,
  ShieldCheck,
  Tag,
  UserRound,
} from "lucide-react"
import { PageHeader } from "@/components/layout/page-header"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import { ConfirmDialog } from "@/components/design-system/confirm-dialog"
import { notify } from "@/components/design-system/notify"
import {
  ALL_VALUE,
  FilterBar,
  FilterSelect,
  SearchFilter,
} from "@/components/design-system/filter-bar"
import { ErrorState } from "@/components/design-system/data-states"
import { ConversationSkeleton, InboxPageSkeleton } from "@/components/design-system/page-skeletons"
import { PlatformBadge, PlatformIcon } from "@/components/design-system/platform-badge"
import { InboxStatusBadge } from "@/components/design-system/inbox-status-badge"
import { UserAvatar } from "@/components/design-system/user-avatar"
import {
  inboxStatusLabels,
  type InboxCase,
  type InboxMessage,
  type InboxStatus,
} from "@/lib/domain/inbox"
import {
  channelLabels,
  deliveryStatusLabels,
} from "@/lib/domain/labels"
import type { Channel, MessageDeliveryStatus, SlaState, User } from "@/lib/domain/types"
import { formatDateTime, formatRelative, formatTime } from "@/lib/format"
import {
  useCurrentUser,
  useInboxCases,
  useInboxMessages,
  useInboxWorkflow,
  useMarkAllResolvedRead,
  useMarkInboxCaseRead,
} from "@/lib/services/queries"
import { getInboxVoteWeight, InboxConflictError } from "@/lib/services/inbox"
import { getEffectiveSlaState } from "@/lib/sla"

type FolderId = "all" | "snoozed" | InboxStatus

const folderItems: {
  id: FolderId
  label: string
  icon: typeof Inbox
}[] = [
  { id: "all", label: "Wszystkie", icon: Inbox },
  { id: "new", label: "Nowe", icon: CircleDot },
  { id: "verification", label: "W trakcie weryfikacji", icon: ShieldCheck },
  { id: "waiting_for_customer", label: "Dopytane", icon: MessageCircleQuestion },
  { id: "partially_ignored", label: "Częściowo zignorowane", icon: MoreHorizontal },
  { id: "snoozed", label: "Odłożone", icon: Archive },
  { id: "ignored", label: "Zignorowane", icon: Ban },
  { id: "resolved", label: "Rozwiązane", icon: CircleCheckBig },
]

const slaLabels: Record<SlaState, string> = {
  on_track: "W normie",
  at_risk: "Ostrzeżenie",
  breached: "Przekroczone",
  paused: "Wstrzymane",
}

const platformOptions = (
  Object.entries(channelLabels) as [Channel, string][]
).map(([value, label]) => ({ value, label }))

const statusOptions = (
  Object.entries(inboxStatusLabels) as [InboxStatus, string][]
).map(([value, label]) => ({ value, label }))

const slaOptions = (Object.entries(slaLabels) as [SlaState, string][]).map(
  ([value, label]) => ({ value, label }),
)

export function CasesPage({
  onlyMine = false,
  initialCaseId,
}: {
  onlyMine?: boolean
  initialCaseId?: string
}) {
  const casesQuery = useInboxCases()
  const currentUserQuery = useCurrentUser()
  const currentUser = currentUserQuery.data
  const markRead = useMarkInboxCaseRead()
  const [selectedId, setSelectedId] = useState<string | undefined>(initialCaseId)
  const [folder, setFolder] = useState<FolderId>("all")
  const [search, setSearch] = useState("")
  const [platform, setPlatform] = useState(ALL_VALUE)
  const [customer, setCustomer] = useState(ALL_VALUE)
  const [status, setStatus] = useState(ALL_VALUE)
  const [owner, setOwner] = useState(ALL_VALUE)
  const [sla, setSla] = useState(ALL_VALUE)
  const [mobileConversationOpen, setMobileConversationOpen] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  const allCases = useMemo(
    () =>
      onlyMine && currentUser
        ? (casesQuery.data ?? []).filter((item) => item.owner?.id === currentUser.id)
        : (casesQuery.data ?? []),
    [casesQuery.data, currentUser, onlyMine],
  )

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])

  const customerOptions = useMemo(
    () =>
      [...new Map(allCases.map((item) => [item.customer.id, item.customer])).values()]
        .sort((a, b) => a.name.localeCompare(b.name, "pl"))
        .map((item) => ({ value: item.id, label: item.name })),
    [allCases],
  )

  const ownerOptions = useMemo(
    () => [
      { value: "unclaimed", label: "Nieprzypisane" },
      ...[
        ...new Map(
          allCases
            .filter((item): item is InboxCase & { owner: NonNullable<InboxCase["owner"]> } => Boolean(item.owner))
            .map((item) => [item.owner.id, item.owner]),
        ).values(),
      ]
        .sort((a, b) => a.fullName.localeCompare(b.fullName, "pl"))
        .map((item) => ({ value: item.id, label: item.fullName })),
    ],
    [allCases],
  )

  const visibleCases = useMemo(() => {
    const normalizedSearch = search.toLocaleLowerCase("pl").trim()
    return allCases
      .filter((item) => {
        if (folder === "snoozed" && !item.snoozedForCurrentUserUntil) return false
        if (folder !== "all" && folder !== "snoozed" && item.status !== folder) return false
        if (platform !== ALL_VALUE && item.platform !== platform) return false
        if (customer !== ALL_VALUE && item.customer.id !== customer) return false
        if (status !== ALL_VALUE && item.status !== status) return false
        if (sla !== ALL_VALUE && item.sla.state !== sla) return false
        if (owner === "unclaimed" && item.owner) return false
        if (owner !== ALL_VALUE && owner !== "unclaimed" && item.owner?.id !== owner) return false
        if (!normalizedSearch) return true
        return [
          item.reference,
          item.subject,
          item.customer.name,
          item.customer.contactName,
          item.sourceChannel,
          item.lastMessagePreview,
          ...item.metadata.tags,
        ].some((value) => value.toLocaleLowerCase("pl").includes(normalizedSearch))
      })
      .sort((a, b) => compareCases(a, b, now))
  }, [allCases, customer, folder, now, owner, platform, search, sla, status])

  useEffect(() => {
    if (!selectedId && visibleCases[0]) {
      setSelectedId(visibleCases[0].id)
    } else if (selectedId && !allCases.some((item) => item.id === selectedId)) {
      setSelectedId(visibleCases[0]?.id)
    }
  }, [allCases, selectedId, visibleCases])

  const selectedCase = allCases.find((item) => item.id === selectedId)
  const messagesQuery = useInboxMessages(selectedCase?.id)
  const markedReadCaseIds = useRef(new Set<string>())

  useEffect(() => {
    if (!selectedCase) return
    if (!selectedCase.unreadForCurrentUser) {
      markedReadCaseIds.current.delete(selectedCase.id)
      return
    }
    if (!markedReadCaseIds.current.has(selectedCase.id)) {
      markedReadCaseIds.current.add(selectedCase.id)
      markRead.mutate(selectedCase.id)
    }
  }, [markRead, selectedCase])

  const selectCase = (item: InboxCase) => {
    setSelectedId(item.id)
    setMobileConversationOpen(true)
  }

  const resetFilters = () => {
    setSearch("")
    setPlatform(ALL_VALUE)
    setCustomer(ALL_VALUE)
    setStatus(ALL_VALUE)
    setOwner(ALL_VALUE)
    setSla(ALL_VALUE)
  }

  const filtersActive = Boolean(
    search ||
      platform !== ALL_VALUE ||
      customer !== ALL_VALUE ||
      status !== ALL_VALUE ||
      owner !== ALL_VALUE ||
      sla !== ALL_VALUE,
  )

  const unreadCount = allCases.filter((item) => item.unreadForCurrentUser).length
  const mobileFolderOptions = folderItems.map((folderItem) => {
    const inFolder =
      folderItem.id === "all"
        ? allCases
        : folderItem.id === "snoozed"
          ? allCases.filter((item) => Boolean(item.snoozedForCurrentUserUntil))
          : allCases.filter((item) => item.status === folderItem.id)
    const unread = inFolder.filter((item) => item.unreadForCurrentUser).length
    return {
      value: folderItem.id,
      label: `${folderItem.label} (${inFolder.length}${unread ? `, ${unread} nowych` : ""})`,
    }
  })

  return (
    <>
      <PageHeader
        title={onlyMine ? "Bieżące case’y" : "Case’y"}
        description={
          onlyMine
            ? "Case’y przypisane do bieżącego użytkownika"
            : "Wspólna skrzynka Slack, Microsoft Teams i Telegram"
        }
        actions={
          <div className="flex items-center gap-2">
            <span className="hidden text-xs text-muted-foreground xl:inline">
              {allCases.length} case’ów · {unreadCount} nieprzeczytanych
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void casesQuery.refetch()}
              disabled={casesQuery.isFetching}
            >
              <RefreshCw className={casesQuery.isFetching ? "animate-spin" : undefined} />
              <span className="hidden sm:inline">Odśwież</span>
            </Button>
          </div>
        }
      />

      <main className="flex min-h-0 flex-1 flex-col bg-muted/20">
        <div className={`${mobileConversationOpen ? "hidden xl:block" : "block"} shrink-0 border-b bg-card px-3 py-2.5`}>
          <div className="flex flex-col gap-2 2xl:flex-row 2xl:items-center">
            <FilterBar className="min-w-0 flex-1">
              <FilterSelect
                value={folder}
                onChange={(value) => setFolder(value as FolderId)}
                options={mobileFolderOptions}
                placeholder="Folder"
                className="xl:hidden sm:w-56"
              />
              <SearchFilter
                value={search}
                onChange={setSearch}
                placeholder="Szukaj po numerze, kliencie lub treści…"
                className="sm:w-72"
              />
              <FilterSelect
                value={platform}
                onChange={setPlatform}
                options={platformOptions}
                placeholder="Platforma"
                allLabel="Wszystkie platformy"
                className="sm:w-42"
              />
              <FilterSelect
                value={customer}
                onChange={setCustomer}
                options={customerOptions}
                placeholder="Klient"
                allLabel="Wszyscy klienci"
                className="sm:w-48"
              />
              <FilterSelect
                value={status}
                onChange={setStatus}
                options={statusOptions}
                placeholder="Status"
                allLabel="Wszystkie statusy"
                className="sm:w-48"
              />
              <FilterSelect
                value={owner}
                onChange={setOwner}
                options={ownerOptions}
                placeholder="Opiekun"
                allLabel="Wszyscy opiekunowie"
                className="sm:w-48"
              />
              <FilterSelect
                value={sla}
                onChange={setSla}
                options={slaOptions}
                placeholder="SLA"
                allLabel="Każdy stan SLA"
                className="sm:w-40"
              />
            </FilterBar>
            {filtersActive && (
              <Button variant="ghost" size="sm" onClick={resetFilters}>
                Wyczyść filtry
              </Button>
            )}
          </div>
        </div>

        {casesQuery.isError || currentUserQuery.isError ? (
          <ErrorState title="Nie udało się wczytać skrzynki" description="Sprawdź połączenie i spróbuj ponownie." onRetry={() => { void casesQuery.refetch(); void currentUserQuery.refetch() }} className="flex-1" />
        ) : casesQuery.isLoading || currentUserQuery.isLoading || !currentUser ? (
          <InboxPageSkeleton />
        ) : (
          <div className="grid min-h-0 flex-1 grid-cols-1 xl:grid-cols-[220px_390px_minmax(0,1fr)]">
            <FolderSidebar
              cases={allCases}
              activeFolder={folder}
              onFolderChange={setFolder}
            />
            <CaseList
              cases={visibleCases}
              activeFolder={folder}
              selectedId={selectedId}
              now={now}
              onSelect={selectCase}
              className={mobileConversationOpen ? "hidden xl:flex" : "flex"}
            />
            <ConversationPanel
              item={selectedCase}
              messages={messagesQuery.data ?? []}
              isLoading={messagesQuery.isLoading}
              isError={messagesQuery.isError}
              onRetry={() => void messagesQuery.refetch()}
              now={now}
              currentUser={currentUser}
              onBack={() => setMobileConversationOpen(false)}
              className={mobileConversationOpen ? "flex" : "hidden xl:flex"}
            />
          </div>
        )}
      </main>
    </>
  )
}

function compareCases(a: InboxCase, b: InboxCase, now: number) {
  const rank = (item: InboxCase) => {
    const slaState = getEffectiveSlaState(item.sla, now)
    if (slaState === "breached") return 0
    if (slaState === "at_risk") return 1
    if (item.unreadForCurrentUser && item.status === "new") return 2
    if (item.status === "new") return 3
    return 4
  }
  return rank(a) - rank(b) || new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
}

function FolderSidebar({
  cases,
  activeFolder,
  onFolderChange,
}: {
  cases: InboxCase[]
  activeFolder: FolderId
  onFolderChange: (folder: FolderId) => void
}) {
  return (
    <aside className="hidden min-h-0 flex-col border-r bg-card xl:flex" aria-label="Foldery skrzynki">
      <div className="border-b px-3 py-3">
        <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Foldery</p>
      </div>
      <nav className="min-h-0 flex-1 overflow-y-auto p-2">
        <ul className="space-y-0.5">
          {folderItems.map((folder) => {
            const inFolder =
              folder.id === "all"
                ? cases
                : folder.id === "snoozed"
                  ? cases.filter((item) => Boolean(item.snoozedForCurrentUserUntil))
                  : cases.filter((item) => item.status === folder.id)
            const unread = inFolder.filter((item) => item.unreadForCurrentUser).length
            const active = activeFolder === folder.id
            return (
              <li key={folder.id}>
                <button
                  type="button"
                  onClick={() => onFolderChange(folder.id)}
                  aria-current={active ? "page" : undefined}
                  className={`flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-sm transition-colors ${
                    active
                      ? "bg-accent font-medium text-accent-foreground"
                      : "text-foreground/80 hover:bg-muted hover:text-foreground"
                  }`}
                >
                  <folder.icon className="size-4 shrink-0 text-muted-foreground" />
                  <span className="min-w-0 flex-1 truncate">{folder.label}</span>
                  {unread > 0 && (
                    <span className="min-w-5 rounded-full bg-primary px-1.5 text-center text-[10px] font-semibold text-primary-foreground">
                      {unread}
                    </span>
                  )}
                  <span className="min-w-5 text-right text-[11px] tabular-nums text-muted-foreground">
                    {inFolder.length}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      </nav>
      <div className="border-t p-3 text-[11px] leading-relaxed text-muted-foreground">
        Nieprzeczytane są liczone osobno dla bieżącego użytkownika.
      </div>
    </aside>
  )
}

function CaseList({
  cases,
  activeFolder,
  selectedId,
  now,
  onSelect,
  className,
}: {
  cases: InboxCase[]
  activeFolder: FolderId
  selectedId?: string
  now: number
  onSelect: (item: InboxCase) => void
  className?: string
}) {
  const markAllRead = useMarkAllResolvedRead()
  const [confirmReadOpen, setConfirmReadOpen] = useState(false)
  const itemRefs = useRef(new Map<string, HTMLButtonElement>())
  const unreadResolved = cases.filter((item) => item.unreadForCurrentUser).length

  const focusCase = (index: number) => {
    const item = cases[Math.max(0, Math.min(cases.length - 1, index))]
    if (item) itemRefs.current.get(item.id)?.focus()
  }

  return (
    <section className={`${className ?? "flex"} min-h-0 min-w-0 flex-col border-r bg-card`} aria-label="Lista case’ów">
      <div className="flex h-10 shrink-0 items-center justify-between border-b px-3">
        <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
          Kolejka
        </span>
        <div className="flex items-center gap-2">
          {activeFolder === "resolved" && (
            <>
              <Button
                variant="ghost"
                size="xs"
                onClick={() => setConfirmReadOpen(true)}
                disabled={unreadResolved === 0 || markAllRead.isPending}
              >
                <CheckCheck /> Oznacz wszystkie jako przeczytane
              </Button>
              <ConfirmDialog
                open={confirmReadOpen}
                onOpenChange={setConfirmReadOpen}
                title="Oznaczyć rozwiązane jako przeczytane?"
                description="Zmiana dotyczy wyłącznie Twojego konta. Stany innych użytkowników pozostaną bez zmian."
                confirmLabel="Oznacz jako przeczytane"
                onConfirm={() =>
                  markAllRead.mutate(undefined, {
                    onSuccess: () => notify.success("Oznaczono jako przeczytane", "Zmieniono tylko Twój osobisty stan odczytu."),
                  })
                }
              />
            </>
          )}
          <span className="text-xs tabular-nums text-muted-foreground">{cases.length} wyników</span>
        </div>
      </div>
      {cases.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-2 p-8 text-center">
          <Inbox className="size-8 text-muted-foreground/50" />
          <p className="text-sm font-medium">Brak pasujących case’ów</p>
          <p className="text-xs text-muted-foreground">Zmień folder lub aktywne filtry.</p>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto" role="listbox" aria-label="Case’y w kolejce">
          {cases.map((item, index) => (
            <CaseListItem
              key={item.id}
              item={item}
              selected={selectedId === item.id}
              now={now}
              onSelect={() => onSelect(item)}
              buttonRef={(node) => {
                if (node) itemRefs.current.set(item.id, node)
                else itemRefs.current.delete(item.id)
              }}
              tabIndex={selectedId === item.id || (!selectedId && index === 0) ? 0 : -1}
              onKeyNavigate={(key) => {
                if (key === "ArrowDown") focusCase(index + 1)
                if (key === "ArrowUp") focusCase(index - 1)
                if (key === "Home") focusCase(0)
                if (key === "End") focusCase(cases.length - 1)
              }}
            />
          ))}
        </div>
      )}
    </section>
  )
}

function CaseListItem({
  item,
  selected,
  now,
  onSelect,
  buttonRef,
  tabIndex,
  onKeyNavigate,
}: {
  item: InboxCase
  selected: boolean
  now: number
  onSelect: () => void
  buttonRef: (node: HTMLButtonElement | null) => void
  tabIndex: number
  onKeyNavigate: (key: "ArrowDown" | "ArrowUp" | "Home" | "End") => void
}) {
  return (
    <button
      type="button"
      ref={buttonRef}
      onClick={onSelect}
      role="option"
      aria-selected={selected}
      tabIndex={tabIndex}
      onKeyDown={(event) => {
        if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
          event.preventDefault()
          onKeyNavigate(event.key as "ArrowDown" | "ArrowUp" | "Home" | "End")
        }
      }}
      className={`group relative flex w-full flex-col gap-2 border-b px-3 py-3 text-left transition-colors ${
        selected ? "bg-accent/70" : "hover:bg-muted/55"
      }`}
    >
      {selected && <span className="absolute inset-y-2 left-0 w-0.5 rounded-r bg-primary" aria-hidden />}
      <div className="flex items-start gap-2.5">
        <PlatformIcon channel={item.platform} className="mt-0.5 size-8" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-xs font-semibold">{item.customer.name}</span>
            {item.unreadForCurrentUser && (
              <span className="inline-flex shrink-0 items-center text-primary" title="Nieprzeczytane"><Mail className="size-3.5" aria-hidden /><span className="sr-only">Nieprzeczytane</span></span>
            )}
            <span className="ml-auto shrink-0 text-[10px] tabular-nums text-muted-foreground">
              {formatRelative(item.updatedAt)}
            </span>
          </div>
          <div className="mt-0.5 flex items-center gap-1.5 text-[10px] text-muted-foreground">
            <span className="truncate">{item.sourceChannel}</span>
            <span aria-hidden>·</span>
            <span className="font-mono">{item.reference}</span>
          </div>
        </div>
      </div>

      <div>
        <p className={`line-clamp-2 text-[13px] leading-snug ${item.unreadForCurrentUser ? "font-semibold" : "font-medium"}`}>
          {item.subject}
        </p>
        <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-muted-foreground">
          {item.lastMessagePreview}
        </p>
      </div>

      <div className="flex items-center gap-1.5">
        <InboxStatusBadge status={item.status} compact />
        <SlaCountdown item={item} now={now} compact />
        {item.ignoreVotes.current > 0 && (
          <Badge variant="secondary" className="border-transparent bg-muted px-1.5 text-[10px] text-muted-foreground">
            <Ban className="size-2.5" /> {item.ignoreVotes.current}/2
          </Badge>
        )}
        <span className="ml-auto flex min-w-0 items-center gap-1.5">
          {item.owner ? (
            <>
              <UserAvatar user={item.owner} size="sm" showPresence />
              <span className="max-w-24 truncate text-[10px] text-muted-foreground">
                {item.owner.fullName.split(" ")[0]}
              </span>
            </>
          ) : (
            <span className="text-[10px] font-medium text-warning-foreground">Nieprzypisany</span>
          )}
        </span>
      </div>
    </button>
  )
}

function ConversationPanel({
  item,
  messages,
  isLoading,
  isError,
  onRetry,
  now,
  currentUser,
  onBack,
  className,
}: {
  item?: InboxCase
  messages: InboxMessage[]
  isLoading: boolean
  isError: boolean
  onRetry: () => void
  now: number
  currentUser: User
  onBack: () => void
  className?: string
}) {
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [mobileDetailsOpen, setMobileDetailsOpen] = useState(false)

  if (!item) {
    return (
      <section className={`${className ?? "flex"} min-h-0 min-w-0 items-center justify-center bg-background`}>
        <div className="text-center">
          <Inbox className="mx-auto size-10 text-muted-foreground/40" />
          <p className="mt-3 text-sm font-medium">Wybierz case z kolejki</p>
          <p className="mt-1 text-xs text-muted-foreground">Rozmowa pojawi się w tym miejscu.</p>
        </div>
      </section>
    )
  }

  return (
    <section className={`${className ?? "flex"} min-h-0 min-w-0 flex-col bg-background`} aria-label={`Rozmowa ${item.reference}`}>
      <header className="shrink-0 border-b bg-card">
        <div className="flex min-w-0 items-start gap-3 px-3 py-3 lg:px-4">
          <Button variant="ghost" size="icon-sm" className="mt-0.5 xl:hidden" onClick={onBack} aria-label="Wróć do listy case’ów">
            <ArrowLeft />
          </Button>
          <PlatformIcon channel={item.platform} className="mt-0.5 size-9" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span className="font-mono text-[11px] font-semibold text-muted-foreground">{item.reference}</span>
              <h2 className="min-w-0 truncate text-sm font-semibold">{item.subject}</h2>
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <span className="font-medium text-foreground">{item.customer.name}</span>
              <span aria-hidden>·</span>
              <PlatformBadge channel={item.platform} />
              <span>{item.sourceChannel}</span>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            <Button variant="outline" size="sm" disabled title="Połączenie ze źródłem nie jest aktywne w makiecie">
              <ExternalLink />
              <span className="hidden 2xl:inline">Otwórz w źródle</span>
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              className="xl:hidden"
              onClick={() => setMobileDetailsOpen(true)}
              aria-label="Pokaż szczegóły case’u"
            >
              <PanelRightOpen />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              className="hidden xl:inline-flex"
              onClick={() => setDetailsOpen((open) => !open)}
              aria-label={detailsOpen ? "Ukryj szczegóły case’u" : "Pokaż szczegóły case’u"}
              aria-pressed={detailsOpen}
            >
              {detailsOpen ? <PanelRightClose /> : <PanelRightOpen />}
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 border-t px-3 py-2 lg:px-4">
          <InboxStatusBadge status={item.status} />
          <SlaCountdown item={item} now={now} />
          <Separator orientation="vertical" className="mx-1 h-5" />
          {item.owner ? (
            <span className="flex items-center gap-1.5 text-xs">
              <UserAvatar user={item.owner} size="sm" showPresence />
              <span className="font-medium">{item.owner.fullName}</span>
            </span>
          ) : (
            <Badge variant="secondary" className="border-transparent bg-warning/15 text-warning-foreground">
              <UserRound /> Nieprzypisany
            </Badge>
          )}
          {item.ignoreVotes.current > 0 && (
            <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
              <Ban className="size-3.5" /> Głosy ignorowania {item.ignoreVotes.current}/2
            </span>
          )}
        </div>
      </header>

      <CaseStateBanner item={item} now={now} currentUser={currentUser} />

      <div className="flex min-h-0 flex-1">
        <div className="flex min-w-0 flex-1 flex-col">
          {isLoading ? (
            <ConversationSkeleton />
          ) : isError ? (
            <ErrorState title="Nie udało się wczytać rozmowy" onRetry={onRetry} className="flex-1" />
          ) : (
            <ConversationStream key={item.id} messages={messages} />
          )}
          <CaseInteractionPanel item={item} currentUser={currentUser} />
        </div>
        {detailsOpen && <CaseDetails item={item} now={now} />}
      </div>
      <Sheet open={mobileDetailsOpen} onOpenChange={setMobileDetailsOpen}>
        <SheetContent side="right" className="w-[min(92vw,23rem)] gap-0 p-0 xl:hidden">
          <SheetHeader className="border-b pr-12">
            <SheetTitle>Szczegóły case’u</SheetTitle>
            <SheetDescription>{item.reference} · {item.customer.name}</SheetDescription>
          </SheetHeader>
          <CaseDetails item={item} now={now} variant="sheet" />
        </SheetContent>
      </Sheet>
    </section>
  )
}

function ConversationStream({ messages }: { messages: InboxMessage[] }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const timeoutRef = useRef<number | undefined>(undefined)
  const restoreRef = useRef<{ height: number; top: number } | undefined>(undefined)
  const [visibleStart, setVisibleStart] = useState(() => Math.max(0, messages.length - 10))
  const [loadingOlder, setLoadingOlder] = useState(false)
  const initialScroll = useRef(true)
  const previousMessageCount = useRef(messages.length)

  useEffect(
    () => () => {
      if (timeoutRef.current) window.clearTimeout(timeoutRef.current)
    },
    [],
  )

  useLayoutEffect(() => {
    const container = containerRef.current
    if (!container) return
    if (restoreRef.current) {
      const restore = restoreRef.current
      container.scrollTop = container.scrollHeight - restore.height + restore.top
      restoreRef.current = undefined
    } else if (initialScroll.current) {
      container.scrollTop = container.scrollHeight
      initialScroll.current = false
    } else if (messages.length > previousMessageCount.current) {
      container.scrollTop = container.scrollHeight
    }
    previousMessageCount.current = messages.length
  }, [visibleStart, messages.length])

  const loadOlder = useCallback(() => {
    const container = containerRef.current
    if (!container || loadingOlder || visibleStart === 0) return
    restoreRef.current = { height: container.scrollHeight, top: container.scrollTop }
    setLoadingOlder(true)
    timeoutRef.current = window.setTimeout(() => {
      setVisibleStart((current) => Math.max(0, current - 6))
      setLoadingOlder(false)
    }, 450)
  }, [loadingOlder, visibleStart])

  const handleScroll = () => {
    if ((containerRef.current?.scrollTop ?? 100) < 56) loadOlder()
  }

  const visibleMessages = messages.slice(visibleStart)
  let previousDate = ""

  return (
    <div
      ref={containerRef}
      onScroll={handleScroll}
      className="min-h-0 flex-1 overflow-y-auto bg-muted/10 px-3 py-4 lg:px-5"
      aria-label="Historia rozmowy"
      role="log"
      aria-live="polite"
      aria-relevant="additions text"
    >
      <div className="mx-auto flex max-w-3xl flex-col gap-3">
        {visibleStart > 0 && (
          <button
            type="button"
            onClick={loadOlder}
            disabled={loadingOlder}
            className="mx-auto flex items-center gap-1.5 rounded-full border bg-card px-3 py-1.5 text-xs text-muted-foreground shadow-sm hover:text-foreground disabled:opacity-60"
          >
            {loadingOlder ? <LoaderCircle className="size-3.5 animate-spin" /> : <Clock3 className="size-3.5" />}
            {loadingOlder ? "Wczytywanie starszych wiadomości…" : "Wczytaj starsze wiadomości"}
          </button>
        )}

        {visibleMessages.map((message) => {
          const dateKey = new Date(message.createdAt).toDateString()
          const showDate = dateKey !== previousDate
          previousDate = dateKey
          return (
            <div key={message.id} className="contents">
              {showDate && <DateSeparator value={message.createdAt} />}
              <MessageBubble message={message} />
            </div>
          )
        })}
      </div>
    </div>
  )
}

function DateSeparator({ value }: { value: string }) {
  const date = new Date(value)
  const today = new Date()
  const yesterday = new Date()
  yesterday.setDate(today.getDate() - 1)
  const label =
    date.toDateString() === today.toDateString()
      ? "Dzisiaj"
      : date.toDateString() === yesterday.toDateString()
        ? "Wczoraj"
        : new Intl.DateTimeFormat("pl-PL", {
            weekday: "long",
            day: "numeric",
            month: "long",
          }).format(date)

  return (
    <div className="sticky top-0 z-10 flex justify-center py-1">
      <span className="rounded-full border bg-background/95 px-2.5 py-1 text-[10px] font-medium text-muted-foreground shadow-sm backdrop-blur">
        {label}
      </span>
    </div>
  )
}

function MessageBubble({ message }: { message: InboxMessage }) {
  if (message.kind === "system") {
    return (
      <div className="flex justify-center py-1">
        <span className="rounded-full bg-muted px-3 py-1 text-[10px] text-muted-foreground">
          {message.body} · {formatTime(message.createdAt)}
        </span>
      </div>
    )
  }

  const support = message.kind === "support"
  return (
    <article className={`flex ${support ? "justify-end" : "justify-start"}`}>
      <div className={`max-w-[82%] ${support ? "items-end" : "items-start"} flex flex-col gap-1`}>
        <div className={`flex items-center gap-2 px-1 text-[10px] text-muted-foreground ${support ? "flex-row-reverse" : ""}`}>
          <span className="font-medium text-foreground/75">{message.sender}</span>
          <span>{formatTime(message.createdAt)}</span>
          {message.edited && <span>edytowano</span>}
        </div>
        <div
          className={`rounded-2xl px-3 py-2.5 text-[13px] leading-relaxed shadow-sm ${
            support
              ? "rounded-tr-sm bg-primary text-primary-foreground"
              : "rounded-tl-sm border bg-card text-card-foreground"
          }`}
        >
          <p className="whitespace-pre-wrap">{message.body}</p>
          {message.attachments && (
            <div className="mt-2 grid gap-1.5">
              {message.attachments.map((attachment) => (
                <div
                  key={attachment.id}
                  className={`flex items-center gap-2 rounded-lg border px-2.5 py-2 ${
                    support ? "border-primary-foreground/25 bg-primary-foreground/10" : "bg-muted/50"
                  }`}
                >
                  {attachment.type === "image" ? (
                    <ImageIcon className="size-4" />
                  ) : attachment.type === "archive" ? (
                    <FileArchive className="size-4" />
                  ) : (
                    <FileText className="size-4" />
                  )}
                  <span className="min-w-0 flex-1 truncate text-xs font-medium">{attachment.fileName}</span>
                  <span className="text-[10px] opacity-75">{attachment.size}</span>
                </div>
              ))}
            </div>
          )}
          {message.codeBlock && (
            <div className="mt-2 overflow-hidden rounded-lg bg-slate-950 text-slate-100 ring-1 ring-white/10">
              <div className="border-b border-white/10 px-3 py-1 text-[10px] text-slate-400">
                {message.codeBlock.language}
              </div>
              <pre className="overflow-x-auto p-3 font-mono text-[11px] leading-relaxed">
                <code>{message.codeBlock.content}</code>
              </pre>
            </div>
          )}
        </div>
        {support && message.deliveryStatus && (
          <DeliveryStatus status={message.deliveryStatus} />
        )}
      </div>
    </article>
  )
}

function DeliveryStatus({ status }: { status: MessageDeliveryStatus }) {
  const Icon = status === "failed"
    ? TriangleAlert
    : status === "queued" || status === "sending"
      ? LoaderCircle
      : status === "read" || status === "delivered"
        ? CheckCheck
        : Check
  return (
    <span className={`flex items-center gap-1 px-1 text-[10px] ${status === "failed" ? "text-destructive" : "text-muted-foreground"}`}>
      <Icon className={`size-3 ${status === "queued" || status === "sending" ? "animate-spin" : ""}`} aria-hidden /> {deliveryStatusLabels[status]}
    </span>
  )
}

function CaseStateBanner({ item, now, currentUser }: { item: InboxCase; now: number; currentUser: User }) {
  const assignedToCurrentUser = item.owner?.id === currentUser.id
  const waiting = item.status === "waiting_for_customer"
  const terminal = item.status === "resolved" || item.status === "ignored"

  if (waiting) {
    const remaining = item.waitingUntil
      ? Math.max(0, Math.ceil((new Date(item.waitingUntil).getTime() - now) / 60_000))
      : 0
    return (
      <div className="flex shrink-0 items-center gap-2 border-b border-warning/25 bg-warning/10 px-4 py-2 text-xs text-warning-foreground">
        <AlarmClock className="size-4" />
        <span className="font-medium">Oczekiwanie na klienta</span>
        <span>
          {remaining > 0
            ? `Rozmowa tylko do odczytu · pozostało ${formatMinutes(remaining)}`
            : "Minął 24-godzinny czas oczekiwania · rozmowa nadal tylko do odczytu"}
        </span>
      </div>
    )
  }

  if (terminal) {
    return (
      <div
        className={`flex shrink-0 items-center gap-2 border-b px-4 py-2 text-xs ${
          item.status === "resolved"
            ? "border-success/20 bg-success/10 text-success"
            : "border-destructive/20 bg-destructive/10 text-destructive"
        }`}
      >
        {item.status === "resolved" ? <CheckCircle2 className="size-4" /> : <Ban className="size-4" />}
        <span className="font-medium">
          {item.status === "resolved" ? "Case rozwiązany" : "Case zignorowany"}
        </span>
        <span>Widok tylko do odczytu.</span>
        {item.resolutionCategory && <span className="ml-auto">Kategoria: {item.resolutionCategory}</span>}
      </div>
    )
  }

  if (item.currentUserRestrictedByIgnore) {
    return (
      <div className="flex shrink-0 items-center gap-2 border-b border-destructive/20 bg-destructive/10 px-4 py-2 text-xs text-destructive">
        <TriangleAlert className="size-4" />
        Po oddaniu głosu ignorowania nie możesz przejąć tego case’u ani na niego odpowiadać.
      </div>
    )
  }

  if (assignedToCurrentUser) {
    return (
      <div className="flex shrink-0 items-center gap-2 border-b border-primary/15 bg-primary/5 px-4 py-2 text-xs text-primary">
        <UserCheck className="size-4" />
        <span className="font-medium">Przypisany do Ciebie</span>
      </div>
    )
  }

  if (item.owner) {
    return (
      <div className="flex shrink-0 items-center gap-2 border-b bg-muted/40 px-4 py-2 text-xs text-muted-foreground">
        <UserRound className="size-4" />
        Przypisany agent: <span className="font-medium text-foreground">{item.owner.fullName}</span>
      </div>
    )
  }

  return null
}

function CaseInteractionPanel({ item, currentUser }: { item: InboxCase; currentUser: User }) {
  const workflow = useInboxWorkflow(item.id)
  const [actionsOpen, setActionsOpen] = useState(false)
  const [ignoreOpen, setIgnoreOpen] = useState(false)
  const [ignoreReason, setIgnoreReason] = useState("")
  const [askOpen, setAskOpen] = useState(false)
  const [askMessage, setAskMessage] = useState("")
  const [resolveOpen, setResolveOpen] = useState(false)
  const [resolutionCategory, setResolutionCategory] = useState("")
  const voteWeight = getInboxVoteWeight(currentUser)

  const assignedToCurrentUser = item.owner?.id === currentUser.id
  const assignedToAnotherUser = Boolean(item.owner && !assignedToCurrentUser)
  const waiting = item.status === "waiting_for_customer"
  const terminal = item.status === "resolved" || item.status === "ignored"
  const eligibleUnassigned =
    !item.owner &&
    !item.currentUserRestrictedByIgnore &&
    (item.status === "new" || item.status === "partially_ignored")
  const composerEnabled =
    assignedToCurrentUser && !waiting && !terminal && !item.currentUserRestrictedByIgnore
  const snoozeEnabled = eligibleUnassigned || (assignedToCurrentUser && !waiting && !terminal)

  const claim = async () => {
    try {
      await workflow.claim.mutateAsync()
      notify.success("Case przejęty", "Status zmieniono na „W trakcie weryfikacji”.")
    } catch (error) {
      notify.error(
        error instanceof InboxConflictError ? "Konflikt 409" : "Nie udało się przejąć case’u",
        error instanceof Error ? error.message : undefined,
      )
    }
  }

  const confirmIgnore = async () => {
    try {
      await workflow.ignore.mutateAsync({ reason: ignoreReason, weight: voteWeight })
      setIgnoreOpen(false)
      setIgnoreReason("")
      notify.success("Głos zapisany", `Dodano ${voteWeight} ${voteWeight === 1 ? "punkt" : "punkty"} ignorowania.`)
    } catch (error) {
      notify.error("Nie udało się zapisać głosu", error instanceof Error ? error.message : undefined)
    }
  }

  const confirmAsk = async () => {
    if (!askMessage.trim()) return
    try {
      await workflow.askCustomer.mutateAsync({ message: askMessage.trim() })
      setAskOpen(false)
      setAskMessage("")
      notify.success("Wiadomość wysłana", "Case oczekuje na klienta przez 24 godziny.")
    } catch (error) {
      notify.error("Nie udało się dopytać klienta", error instanceof Error ? error.message : undefined)
    }
  }

  const confirmResolve = async () => {
    try {
      await workflow.resolve.mutateAsync({ category: resolutionCategory || undefined })
      setResolveOpen(false)
      setResolutionCategory("")
      notify.success("Case rozwiązany", "Rozmowa została przełączona w tryb tylko do odczytu.")
    } catch (error) {
      notify.error("Nie udało się rozwiązać case’u", error instanceof Error ? error.message : undefined)
    }
  }

  return (
    <div className="shrink-0 border-t bg-card">
      <div className="hidden flex-wrap items-center gap-1.5 border-b px-3 py-2 md:flex">
        <Button
          variant="outline"
          size="sm"
          disabled={!eligibleUnassigned || workflow.claim.isPending}
          onClick={() => void claim()}
        >
          <UserCheck /> Przejmij
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={!eligibleUnassigned}
          onClick={() => setIgnoreOpen(true)}
        >
          <Ban /> Ignoruj
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={!eligibleUnassigned}
          onClick={() => setAskOpen(true)}
        >
          <MessageSquareMore /> Dopytaj
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={!assignedToCurrentUser || waiting || terminal}
          onClick={() => setResolveOpen(true)}
        >
          <CheckCircle2 /> Rozwiąż
        </Button>
        <SnoozeControl
          enabled={snoozeEnabled}
          pending={workflow.snooze.isPending}
          onSnooze={async (until) => {
            try {
              await workflow.snooze.mutateAsync(until)
              notify.success("Case odłożony", "Zmiana jest widoczna tylko w Twoim folderze „Odłożone”.")
            } catch (error) {
              notify.error("Nie udało się odłożyć case’u", error instanceof Error ? error.message : undefined)
              throw error
            }
          }}
        />

        {eligibleUnassigned && (
          <Button
            variant="ghost"
            size="xs"
            className="ml-auto text-muted-foreground"
            onClick={() =>
              notify.error(
                "Konflikt 409 — tryb demonstracyjny",
                "Inny agent przejął case ułamek sekundy wcześniej. Odśwież kolejkę i wybierz ponownie.",
              )
            }
          >
            Demo 409
          </Button>
        )}
        {assignedToAnotherUser && (
          <span className="ml-auto text-xs text-muted-foreground">
            Akcje zablokowane · {item.owner?.fullName}
          </span>
        )}
      </div>

      <div className="flex items-center gap-2 border-b px-3 py-2 md:hidden">
        <Button variant="outline" className="w-full justify-between" onClick={() => setActionsOpen(true)} aria-haspopup="dialog">
          <span className="flex items-center gap-2"><MoreHorizontal /> Akcje case’u</span>
          <span className="max-w-36 truncate text-xs font-normal text-muted-foreground">{assignedToCurrentUser ? "Przypisany do Ciebie" : item.owner ? item.owner.fullName : "Nieprzypisany"}</span>
        </Button>
      </div>

      <Sheet open={actionsOpen} onOpenChange={setActionsOpen}>
        <SheetContent side="bottom" className="max-h-[85svh] gap-0 overflow-y-auto rounded-t-2xl p-0 md:hidden">
          <SheetHeader className="border-b pr-12">
            <SheetTitle>Akcje case’u {item.reference}</SheetTitle>
            <SheetDescription>Dostępne operacje zależą od statusu i przypisanego użytkownika.</SheetDescription>
          </SheetHeader>
          <div className="grid gap-2 p-4">
            <Button variant="outline" className="justify-start" disabled={!eligibleUnassigned || workflow.claim.isPending} onClick={() => { setActionsOpen(false); void claim() }}><UserCheck /> Przejmij</Button>
            <Button variant="outline" className="justify-start" disabled={!eligibleUnassigned} onClick={() => { setActionsOpen(false); setIgnoreOpen(true) }}><Ban /> Ignoruj</Button>
            <Button variant="outline" className="justify-start" disabled={!eligibleUnassigned} onClick={() => { setActionsOpen(false); setAskOpen(true) }}><MessageSquareMore /> Dopytaj</Button>
            <Button variant="outline" className="justify-start" disabled={!assignedToCurrentUser || waiting || terminal} onClick={() => { setActionsOpen(false); setResolveOpen(true) }}><CheckCircle2 /> Rozwiąż</Button>
            <SnoozeControl
              enabled={snoozeEnabled}
              pending={workflow.snooze.isPending}
              className="w-full justify-start"
              onSnooze={async (until) => {
                try {
                  await workflow.snooze.mutateAsync(until)
                  setActionsOpen(false)
                  notify.success("Case odłożony", "Zmiana jest widoczna tylko w Twoim folderze „Odłożone”.")
                } catch (error) {
                  notify.error("Nie udało się odłożyć case’u", error instanceof Error ? error.message : undefined)
                  throw error
                }
              }}
            />
            {assignedToAnotherUser && <p className="rounded-lg bg-muted p-3 text-xs text-muted-foreground">Akcje są zablokowane, ponieważ case jest przypisany do: {item.owner?.fullName}.</p>}
          </div>
        </SheetContent>
      </Sheet>

      <MessageComposer
        enabled={composerEnabled}
        unassigned={!item.owner}
        readOnlyReason={
          waiting
            ? "Case oczekuje na odpowiedź klienta."
            : terminal
              ? "Case jest zakończony."
              : assignedToAnotherUser
                ? `Case jest przypisany do: ${item.owner?.fullName}.`
                : item.currentUserRestrictedByIgnore
                  ? "Oddałeś głos ignorowania dla tego case’u."
                  : undefined
        }
        sendMessage={workflow.sendMessage.mutateAsync}
      />

      <Dialog open={ignoreOpen} onOpenChange={setIgnoreOpen}>
        <DialogContent className="sm:max-w-lg" showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>Oddaj głos ignorowania</DialogTitle>
            <DialogDescription>
              Głos wpływa na wspólny status case’u i jest nieodwracalny dla bieżącego użytkownika.
            </DialogDescription>
          </DialogHeader>
          <div className="rounded-lg border border-destructive/25 bg-destructive/10 p-3 text-xs leading-relaxed text-destructive">
            <div className="flex items-start gap-2">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" />
              Po zagłosowaniu nie będziesz już mógł przejąć tego case’u ani na niego odpowiedzieć.
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="rounded-lg border p-3">
              <p className="text-[10px] text-muted-foreground uppercase">Aktualne punkty</p>
              <p className="mt-1 text-xl font-semibold">{item.ignoreVotes.current}/2</p>
            </div>
            <div className="rounded-lg border p-3">
              <p className="text-[10px] text-muted-foreground uppercase">Waga Twojego głosu</p>
              <p className="mt-1 text-xl font-semibold">{voteWeight}</p>
            </div>
          </div>
          <div className="rounded-lg bg-muted/50 p-3 text-xs text-muted-foreground">
            <p className="font-medium text-foreground">Przykładowe wagi</p>
            <p className="mt-1">Agent wsparcia: 1 punkt · Kierownik lub administrator: 2 punkty.</p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="ignore-reason">Powód (opcjonalnie)</Label>
            <textarea
              id="ignore-reason"
              value={ignoreReason}
              onChange={(event) => setIgnoreReason(event.target.value)}
              placeholder="Np. wiadomość testowa lub duplikat…"
              className="min-h-20 w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setIgnoreOpen(false)}>Anuluj</Button>
            <Button variant="destructive" onClick={() => void confirmIgnore()} disabled={workflow.ignore.isPending}>
              {workflow.ignore.isPending && <LoaderCircle className="animate-spin" />}
              Oddaj głos ({voteWeight})
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={askOpen} onOpenChange={setAskOpen}>
        <DialogContent className="sm:max-w-lg" showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>Dopytaj klienta</DialogTitle>
            <DialogDescription>
              Wiadomość jest wymagana. Po wysłaniu case stanie się nieprzypisany i będzie czekać na odpowiedź przez 24 godziny.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-wrap gap-1.5">
            {[
              "Proszę o identyfikator operacji.",
              "Czy problem nadal występuje?",
              "Proszę o zrzut ekranu i godzinę zdarzenia.",
            ].map((suggestion) => (
              <Button key={suggestion} variant="outline" size="xs" onClick={() => setAskMessage(suggestion)}>
                {suggestion}
              </Button>
            ))}
          </div>
          <div className="space-y-2">
            <Label htmlFor="ask-message">Wiadomość do klienta</Label>
            <textarea
              id="ask-message"
              required
              value={askMessage}
              onChange={(event) => setAskMessage(event.target.value)}
              placeholder="Wpisz pytanie do klienta…"
              className="min-h-28 w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            />
          </div>
          <div className="flex items-start gap-2 rounded-lg bg-warning/10 p-3 text-xs leading-relaxed text-warning-foreground">
            <AlarmClock className="mt-0.5 size-4 shrink-0" />
            Rozmowa zostanie przełączona w tryb tylko do odczytu do czasu odpowiedzi klienta lub upływu 24 godzin.
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAskOpen(false)}>Anuluj</Button>
            <Button onClick={() => void confirmAsk()} disabled={!askMessage.trim() || workflow.askCustomer.isPending}>
              {workflow.askCustomer.isPending && <LoaderCircle className="animate-spin" />}
              Wyślij i oczekuj
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={resolveOpen} onOpenChange={setResolveOpen}>
        <DialogContent showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>Oznaczyć case jako rozwiązany?</DialogTitle>
            <DialogDescription>
              Case otrzyma status końcowy, a rozmowa stanie się dostępna tylko do odczytu.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="resolution-category">Kategoria rozwiązania (opcjonalnie)</Label>
            <select
              id="resolution-category"
              value={resolutionCategory}
              onChange={(event) => setResolutionCategory(event.target.value)}
              className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <option value="">Bez kategorii</option>
              <option value="Naprawa konfiguracji">Naprawa konfiguracji</option>
              <option value="Wyjaśnienie klientowi">Wyjaśnienie klientowi</option>
              <option value="Błąd produktu naprawiony">Błąd produktu naprawiony</option>
              <option value="Brak możliwości odtworzenia">Brak możliwości odtworzenia</option>
            </select>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setResolveOpen(false)}>Anuluj</Button>
            <Button onClick={() => void confirmResolve()} disabled={workflow.resolve.isPending}>
              {workflow.resolve.isPending && <LoaderCircle className="animate-spin" />}
              Potwierdź rozwiązanie
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function SnoozeControl({
  enabled,
  pending,
  onSnooze,
  className,
}: {
  enabled: boolean
  pending: boolean
  onSnooze: (until: string) => Promise<void>
  className?: string
}) {
  const [open, setOpen] = useState(false)
  const [customDate, setCustomDate] = useState("")

  const snooze = async (date: Date) => {
    await onSnooze(date.toISOString())
    setOpen(false)
    setCustomDate("")
  }

  const tomorrowMorning = () => {
    const date = new Date()
    date.setDate(date.getDate() + 1)
    date.setHours(9, 0, 0, 0)
    return date
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          <Button variant="outline" size="sm" disabled={!enabled || pending} className={className}>
            <AlarmClock /> Odłóż na później
          </Button>
        }
      />
      <PopoverContent align="start" className="w-80">
        <PopoverHeader>
          <PopoverTitle>Odłóż na później</PopoverTitle>
          <PopoverDescription>
            To ustawienie jest osobiste. Inni agenci nadal zobaczą case w swoich kolejkach.
          </PopoverDescription>
        </PopoverHeader>
        <div className="grid grid-cols-2 gap-1.5">
          {[
            ["15 min", 15],
            ["30 min", 30],
            ["1 godzina", 60],
            ["2 godziny", 120],
          ].map(([label, minutes]) => (
            <Button
              key={String(label)}
              variant="outline"
              size="sm"
              onClick={() => void snooze(new Date(Date.now() + Number(minutes) * 60_000))}
            >
              {label}
            </Button>
          ))}
        </div>
        <Button variant="outline" size="sm" onClick={() => void snooze(tomorrowMorning())}>
          Jutro rano · 09:00
        </Button>
        <Separator />
        <div className="space-y-2">
          <Label htmlFor="custom-snooze">Własny termin</Label>
          <div className="flex gap-2">
            <Input
              id="custom-snooze"
              type="datetime-local"
              value={customDate}
              min={toDateTimeLocal(new Date())}
              onChange={(event) => setCustomDate(event.target.value)}
            />
            <Button
              size="sm"
              disabled={!customDate}
              onClick={() => void snooze(new Date(customDate))}
            >
              Ustaw
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

function MessageComposer({
  enabled,
  unassigned,
  readOnlyReason,
  sendMessage,
}: {
  enabled: boolean
  unassigned: boolean
  readOnlyReason?: string
  sendMessage: (input: {
    body: string
    attachments?: { fileName: string; size: string }[]
    simulateFailure?: boolean
  }) => Promise<unknown>
}) {
  const [draft, setDraft] = useState("")
  const [attachments, setAttachments] = useState<{ fileName: string; size: string }[]>([])
  const [simulateFailure, setSimulateFailure] = useState(false)
  const [sendState, setSendState] = useState<"idle" | "pending" | "sent" | "failed">("idle")
  const fileInputRef = useRef<HTMLInputElement>(null)
  const sentResetTimer = useRef<number | undefined>(undefined)

  useEffect(
    () => () => {
      if (sentResetTimer.current) window.clearTimeout(sentResetTimer.current)
    },
    [],
  )

  const performSend = async (retry = false) => {
    if (!enabled || (!draft.trim() && attachments.length === 0)) return
    setSendState("pending")
    try {
      await sendMessage({
        body: draft.trim() || "Załącznik",
        attachments,
        simulateFailure: retry ? false : simulateFailure,
      })
      setDraft("")
      setAttachments([])
      setSimulateFailure(false)
      setSendState("sent")
      sentResetTimer.current = window.setTimeout(() => setSendState("idle"), 1800)
    } catch (error) {
      setSendState("failed")
      notify.error("Wiadomość nie została wysłana", error instanceof Error ? error.message : undefined)
    }
  }

  const insertText = (value: string) => {
    if (!enabled) return
    if (sendState === "failed") setSendState("idle")
    setDraft((current) => `${current}${current ? " " : ""}${value}`)
  }

  return (
    <div className="sticky bottom-0 z-20 bg-card p-3 md:static">
      {unassigned && !readOnlyReason && (
        <div className="mb-2 flex items-center gap-2 rounded-lg bg-warning/10 px-3 py-2 text-xs font-medium text-warning-foreground">
          <LockKeyhole className="size-3.5" />
          Aby odpowiedzieć, najpierw przejmij case.
        </div>
      )}
      {!enabled && readOnlyReason && (
        <div className="mb-2 flex items-center gap-2 rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
          <LockKeyhole className="size-3.5" /> {readOnlyReason}
        </div>
      )}

      <div className={`overflow-hidden rounded-lg border bg-background ${enabled ? "focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/30" : "opacity-70"}`}>
        <textarea
          id="case-message-composer"
          aria-label="Treść odpowiedzi do klienta"
          aria-describedby={enabled ? "composer-shortcut" : undefined}
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
            if (sendState === "failed") setSendState("idle")
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
              event.preventDefault()
              void performSend()
            }
          }}
          disabled={!enabled || sendState === "pending"}
          rows={3}
          placeholder={enabled ? "Napisz odpowiedź…" : "Odpowiadanie jest zablokowane"}
          className="block max-h-40 min-h-18 w-full resize-y bg-transparent px-3 py-2 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed"
        />

        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-1.5 px-3 pb-2">
            {attachments.map((attachment) => (
              <span key={attachment.fileName} className="flex items-center gap-1 rounded-md bg-muted px-2 py-1 text-[10px]">
                <Paperclip className="size-3" /> {attachment.fileName} · {attachment.size}
                <button
                  type="button"
                  onClick={() => setAttachments((current) => current.filter((item) => item !== attachment))}
                  className="ml-1 text-muted-foreground hover:text-foreground"
                  aria-label={`Usuń ${attachment.fileName}`}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}

        <div className="flex flex-wrap items-center gap-1 border-t bg-muted/25 px-2 py-1.5">
          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={(event) => {
              const files = Array.from(event.target.files ?? [])
              setAttachments((current) => [
                ...current,
                ...files.map((file) => ({ fileName: file.name, size: formatFileSize(file.size) })),
              ])
              if (sendState === "failed") setSendState("idle")
              event.target.value = ""
            }}
          />
          <Button variant="ghost" size="icon-xs" disabled={!enabled} onClick={() => fileInputRef.current?.click()} aria-label="Dodaj załącznik" title="Dodaj załącznik">
            <Paperclip />
          </Button>
          <Button variant="ghost" size="icon-xs" disabled={!enabled} onClick={() => insertText("🙂")} aria-label="Dodaj emoji" title="Dodaj emoji">
            <Smile />
          </Button>
          <Button variant="ghost" size="icon-xs" disabled={!enabled} onClick={() => insertText("**pogrubienie**")} aria-label="Dodaj pogrubienie" title="Dodaj pogrubienie">
            <Bold />
          </Button>
          <Button
            variant={simulateFailure ? "destructive" : "ghost"}
            size="xs"
            disabled={!enabled || sendState === "pending"}
            onClick={() => {
              setSimulateFailure((active) => !active)
              if (sendState === "failed") setSendState("idle")
            }}
            title="Włącza jednorazowy symulowany błąd wysyłki"
          >
            <TriangleAlert /> Tryb błędu
          </Button>

          <div className="ml-auto flex items-center gap-2" aria-live="polite">
            {sendState === "pending" && (
              <span className="flex items-center gap-1 text-[10px] text-muted-foreground">
                <LoaderCircle className="size-3 animate-spin" /> Wysyłanie…
              </span>
            )}
            {sendState === "sent" && (
              <span className="flex items-center gap-1 text-[10px] text-success">
                <CheckCheck className="size-3" /> Wysłano
              </span>
            )}
            {sendState === "failed" && (
              <span className="flex items-center gap-1.5 text-[10px] text-destructive">
                <TriangleAlert className="size-3" /> Wysyłka nieudana
                <Button variant="destructive" size="xs" onClick={() => void performSend(true)}>
                  <RotateCcw /> Ponów
                </Button>
              </span>
            )}
            {sendState !== "failed" && (
              <Button
                size="sm"
                disabled={!enabled || sendState === "pending" || (!draft.trim() && attachments.length === 0)}
                onClick={() => void performSend()}
              >
                <Send /> Wyślij
              </Button>
            )}
          </div>
        </div>
      </div>
      {enabled && <p id="composer-shortcut" className="mt-1.5 text-[10px] text-muted-foreground">Ctrl/⌘ + Enter, aby wysłać</p>}
    </div>
  )
}

function toDateTimeLocal(date: Date) {
  const offset = date.getTimezoneOffset()
  return new Date(date.getTime() - offset * 60_000).toISOString().slice(0, 16)
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function CaseDetails({ item, now, variant = "inline" }: { item: InboxCase; now: number; variant?: "inline" | "sheet" }) {
  return (
    <aside className={variant === "sheet" ? "min-h-0 w-full flex-1 overflow-y-auto bg-card" : "hidden w-72 shrink-0 overflow-y-auto border-l bg-card xl:block"} aria-label="Szczegóły case’u">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <h3 className="text-sm font-semibold">Szczegóły case’u</h3>
        <Badge variant="outline" className="font-mono text-[10px]">{item.reference}</Badge>
      </div>
      <div className="space-y-5 p-4">
        <DetailSection title="Metadane">
          <DetailRow label="Klient" value={item.customer.name} />
          <DetailRow label="Kontakt" value={item.customer.contactName} />
          <DetailRow label="Priorytet" value={item.metadata.priority} />
          <DetailRow label="Kategoria" value={item.metadata.category} />
          <DetailRow label="Produkt" value={item.metadata.product} />
          <DetailRow label="Środowisko" value={item.metadata.environment} />
          <DetailRow label="Utworzono" value={formatDateTime(item.createdAt)} />
        </DetailSection>

        <DetailSection title="SLA">
          <div className="rounded-lg border bg-muted/20 p-3">
            <SlaCountdown item={item} now={now} />
            <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
              Termin: {item.sla.dueAt ? formatDateTime(item.sla.dueAt) : "wstrzymany"}
            </p>
          </div>
        </DetailSection>

        <DetailSection title="Głosy ignorowania">
          <div className="rounded-lg border p-3">
            <div className="flex items-center justify-between">
              <span className="text-xs text-muted-foreground">Wymagana większość</span>
              <span className="font-mono text-xs font-semibold">{item.ignoreVotes.current}/2</span>
            </div>
            <div className="mt-2 flex gap-1.5">
              {[0, 1].map((vote) => (
                <span
                  key={vote}
                  className={`h-1.5 flex-1 rounded-full ${vote < item.ignoreVotes.current ? "bg-destructive" : "bg-muted"}`}
                />
              ))}
            </div>
            {item.ignoreVotes.voters.length > 0 && (
              <p className="mt-2 text-[11px] text-muted-foreground">
                {item.ignoreVotes.voters.join(", ")}
              </p>
            )}
          </div>
        </DetailSection>

        {item.relatedCase && (
          <DetailSection title="Powiązany case">
            <div className="rounded-lg border p-3">
              <div className="flex items-center gap-2 text-xs font-medium text-primary">
                <Link2 className="size-3.5" /> {item.relatedCase.reference}
              </div>
              <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                {item.relatedCase.subject}
              </p>
            </div>
          </DetailSection>
        )}

        <DetailSection title="Tagi">
          <div className="flex flex-wrap gap-1.5">
            {item.metadata.tags.map((tag) => (
              <Badge key={tag} variant="secondary" className="border-transparent text-[10px]">
                <Tag /> {tag}
              </Badge>
            ))}
          </div>
        </DetailSection>

        <DetailSection title="Dziennik aktywności">
          <ol className="space-y-3">
            {[...item.activity].reverse().map((event, index) => (
              <li key={event.id} className="relative flex gap-2.5 text-xs">
                <span className="mt-1 flex size-5 shrink-0 items-center justify-center rounded-full bg-muted">
                  <Activity className="size-3 text-muted-foreground" />
                </span>
                {index < item.activity.length - 1 && <span className="absolute top-6 bottom-[-12px] left-2.5 w-px bg-border" />}
                <div className="min-w-0">
                  <p className="leading-relaxed">{event.label}</p>
                  <p className="mt-0.5 text-[10px] text-muted-foreground">
                    {event.author ? `${event.author} · ` : ""}{formatRelative(event.createdAt)}
                  </p>
                </div>
              </li>
            ))}
          </ol>
        </DetailSection>
      </div>
    </aside>
  )
}

function DetailSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h4 className="mb-2 text-[10px] font-semibold tracking-wide text-muted-foreground uppercase">{title}</h4>
      {children}
    </section>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-3 border-b py-2 text-xs last:border-0">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="max-w-40 text-right font-medium text-pretty">{value}</dd>
    </div>
  )
}

function SlaCountdown({
  item,
  now,
  compact = false,
}: {
  item: InboxCase
  now: number
  compact?: boolean
}) {
  const dueAt = item.sla.dueAt ? new Date(item.sla.dueAt).getTime() : undefined
  const remainingMinutes = dueAt ? Math.round((dueAt - now) / 60_000) : undefined
  const terminal = item.status === "resolved" || item.status === "ignored"
  const effectiveState = getEffectiveSlaState(item.sla, now)
  const label =
    terminal
      ? "SLA zakończone"
      : effectiveState === "paused" || remainingMinutes === undefined
        ? "SLA wstrzymane"
      : remainingMinutes < 0
        ? `SLA +${formatMinutes(Math.abs(remainingMinutes))}`
        : `SLA ${formatMinutes(remainingMinutes)}`
  const className =
    effectiveState === "breached"
      ? "bg-destructive/10 text-destructive"
      : effectiveState === "at_risk"
        ? "bg-warning/15 text-warning-foreground"
        : effectiveState === "paused"
          ? "bg-muted text-muted-foreground"
          : "bg-success/10 text-success"

  return (
    <Badge
      variant="secondary"
      className={`border-transparent tabular-nums ${className} ${compact ? "px-1.5 text-[10px]" : ""}`}
    >
      <Clock3 /> {label}
    </Badge>
  )
}

function formatMinutes(minutes: number) {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours} godz. ${rest} min` : `${hours} godz.`
}
