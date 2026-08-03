"use client"

import { useEffect, useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import {
  ExternalLink,
  MoreHorizontal,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  UserRoundCog,
  UserRoundX,
} from "lucide-react"
import { PageHeader } from "@/components/layout/page-header"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ConfirmDialog } from "@/components/design-system/confirm-dialog"
import { DataTable, type DataTableColumn } from "@/components/design-system/data-table"
import { EmptyState, ErrorState } from "@/components/design-system/data-states"
import {
  ALL_VALUE,
  FilterBar,
  FilterSelect,
} from "@/components/design-system/filter-bar"
import { notify } from "@/components/design-system/notify"
import { PlatformBadge } from "@/components/design-system/platform-badge"
import { InboxStatusBadge } from "@/components/design-system/inbox-status-badge"
import { SlaIndicator } from "@/components/design-system/sla-indicator"
import { UserAvatar } from "@/components/design-system/user-avatar"
import type { CurrentCaseAdminItem } from "@/lib/domain/analytics"
import { inboxStatusLabels, type InboxStatus } from "@/lib/domain/inbox"
import { channelLabels } from "@/lib/domain/labels"
import type { Channel, SlaState } from "@/lib/domain/types"
import { formatDateTime, formatRelative } from "@/lib/format"
import {
  useCurrentAdministrationUser,
  useCurrentCaseActions,
  useCurrentCases,
  useUsers,
} from "@/lib/services/queries"

const slaLabels: Record<SlaState, string> = {
  on_track: "W normie",
  at_risk: "Ostrzeżenie",
  breached: "Przekroczone",
  paused: "Wstrzymane",
}

export default function CurrentCasesRoute() {
  const router = useRouter()
  const currentCasesQuery = useCurrentCases()
  const usersQuery = useUsers()
  const currentAdministrationUserQuery = useCurrentAdministrationUser()
  const actions = useCurrentCaseActions()
  const [user, setUser] = useState(ALL_VALUE)
  const [status, setStatus] = useState(ALL_VALUE)
  const [platform, setPlatform] = useState(ALL_VALUE)
  const [customer, setCustomer] = useState(ALL_VALUE)
  const [sla, setSla] = useState(ALL_VALUE)
  const [reassigned, setReassigned] = useState<CurrentCaseAdminItem | null>(null)
  const [assigneeId, setAssigneeId] = useState("")
  const [unassigned, setUnassigned] = useState<CurrentCaseAdminItem | null>(null)
  const [resolved, setResolved] = useState<CurrentCaseAdminItem | null>(null)
  const [, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])

  const cases = currentCasesQuery.data ?? []
  const agents = useMemo(
    () => (usersQuery.data ?? []).filter((item) => !item.fullName.startsWith("Klient")),
    [usersQuery.data],
  )
  const currentPermissions = currentAdministrationUserQuery.data?.permissions ?? []
  const canReassign = currentPermissions.includes("reassign_cases")
  const canForceResolve = currentPermissions.includes("force_resolve")
  const customerOptions = useMemo(
    () => [...new Map(cases.map((item) => [item.customer.id, item.customer])).values()]
      .sort((a, b) => a.name.localeCompare(b.name, "pl"))
      .map((item) => ({ value: item.id, label: item.name })),
    [cases],
  )
  const visibleCases = useMemo(
    () => cases.filter((item) => {
      if (user === "unassigned" && item.owner) return false
      if (user !== ALL_VALUE && user !== "unassigned" && item.owner?.id !== user) return false
      if (status !== ALL_VALUE && item.status !== status) return false
      if (platform !== ALL_VALUE && item.platform !== platform) return false
      if (customer !== ALL_VALUE && item.customer.id !== customer) return false
      if (sla !== ALL_VALUE && item.sla.state !== sla) return false
      return true
    }),
    [cases, customer, platform, sla, status, user],
  )

  const columns = useMemo<DataTableColumn<CurrentCaseAdminItem>[]>(
    () => [
      {
        id: "case",
        header: "Case",
        className: "min-w-64",
        cell: (item) => <div><p className="font-mono text-xs font-semibold text-primary">{item.reference}</p><p className="max-w-72 truncate text-sm font-medium">{item.subject}</p></div>,
      },
      {
        id: "customer",
        header: "Klient",
        className: "min-w-44",
        cell: (item) => <div><p className="font-medium">{item.customer.name}</p><p className="text-xs text-muted-foreground">{item.sourceChannel}</p></div>,
      },
      {
        id: "platform",
        header: "Platforma",
        cell: (item) => <PlatformBadge channel={item.platform} />,
      },
      {
        id: "status",
        header: "Status",
        cell: (item) => <InboxStatusBadge status={item.status} />,
      },
      {
        id: "owner",
        header: "Użytkownik",
        className: "min-w-44",
        cell: (item) => item.owner ? <div className="flex items-center gap-2"><UserAvatar user={item.owner} size="sm" showPresence /><span className="text-sm">{item.owner.fullName}</span></div> : <span className="text-xs text-muted-foreground">Nieprzypisany</span>,
      },
      {
        id: "claimed",
        header: "Przejęty",
        className: "min-w-36 text-xs",
        cell: (item) => item.claimedAt ? <div><p>{formatDateTime(item.claimedAt)}</p><p className="text-muted-foreground">{formatRelative(item.claimedAt)}</p></div> : <span className="text-muted-foreground">—</span>,
      },
      {
        id: "activity",
        header: "Ostatnia aktywność",
        className: "min-w-36 text-xs",
        cell: (item) => <div><p>{formatDateTime(item.updatedAt)}</p><p className="text-muted-foreground">{formatRelative(item.updatedAt)}</p></div>,
      },
      {
        id: "sla",
        header: "SLA",
        cell: (item) => <SlaCell item={item} />,
      },
      {
        id: "actions",
        header: <span className="sr-only">Akcje</span>,
        align: "end",
        cell: (item) => (
          <DropdownMenu>
            <DropdownMenuTrigger render={<Button variant="ghost" size="icon-sm" aria-label={`Akcje administracyjne dla ${item.reference}`} />}><MoreHorizontal /></DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuItem onClick={() => router.push(`/cases?caseId=${item.id}`)}><ExternalLink /> Otwórz case</DropdownMenuItem>
              <DropdownMenuItem disabled={!canReassign} onClick={() => { setReassigned(item); setAssigneeId(item.owner?.id ?? agents[0]?.id ?? "") }}><UserRoundCog /> Przepisz użytkownika</DropdownMenuItem>
              <DropdownMenuItem disabled={!item.owner || !canReassign} onClick={() => setUnassigned(item)}><UserRoundX /> Usuń przypisanie</DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem variant="destructive" disabled={!canForceResolve} onClick={() => setResolved(item)}><RotateCcw /> Wymuś rozwiązanie</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        ),
      },
    ],
    [agents, canForceResolve, canReassign, router],
  )

  const clearFilters = () => {
    setUser(ALL_VALUE)
    setStatus(ALL_VALUE)
    setPlatform(ALL_VALUE)
    setCustomer(ALL_VALUE)
    setSla(ALL_VALUE)
  }
  const filtersActive = [user, status, platform, customer, sla].some((value) => value !== ALL_VALUE)

  return (
    <>
      <PageHeader
        title="Bieżące case’y"
        description="Administracyjny przegląd aktywnej pracy zespołu"
        actions={<Button variant="outline" size="sm" onClick={() => void currentCasesQuery.refetch()} disabled={currentCasesQuery.isFetching}><RefreshCw className={currentCasesQuery.isFetching ? "animate-spin" : undefined} /> Odśwież</Button>}
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1800px] flex-col gap-3 p-4 md:p-6">
          <Alert className="bg-info/5 text-info">
            <ShieldCheck />
            <AlertTitle>Audyt działań administracyjnych</AlertTitle>
            <AlertDescription>Przepisanie, usunięcie przypisania i wymuszone rozwiązanie dodają widoczny wpis do historii aktywności case’a z datą oraz wykonawcą.</AlertDescription>
          </Alert>

          <div className="rounded-lg border bg-card p-3">
            <FilterBar>
              <FilterSelect value={user} onChange={setUser} placeholder="Użytkownik" allLabel="Wszyscy użytkownicy" options={[{ value: "unassigned", label: "Nieprzypisane" }, ...agents.map((item) => ({ value: item.id, label: item.fullName }))]} />
              <FilterSelect value={status} onChange={setStatus} placeholder="Status" allLabel="Wszystkie statusy" options={(Object.entries(inboxStatusLabels) as [InboxStatus, string][]).filter(([value]) => value !== "resolved" && value !== "ignored").map(([value, label]) => ({ value, label }))} />
              <FilterSelect value={platform} onChange={setPlatform} placeholder="Platforma" allLabel="Wszystkie platformy" options={(Object.entries(channelLabels) as [Channel, string][]).map(([value, label]) => ({ value, label }))} />
              <FilterSelect value={customer} onChange={setCustomer} placeholder="Klient" allLabel="Wszyscy klienci" options={customerOptions} />
              <FilterSelect value={sla} onChange={setSla} placeholder="Stan SLA" allLabel="Wszystkie stany SLA" options={(Object.entries(slaLabels) as [SlaState, string][]).map(([value, label]) => ({ value, label }))} />
              {filtersActive && <Button variant="ghost" size="sm" onClick={clearFilters}>Wyczyść filtry</Button>}
            </FilterBar>
          </div>

          {currentCasesQuery.isError || usersQuery.isError || currentAdministrationUserQuery.isError ? (
            <div className="rounded-lg border bg-card"><ErrorState onRetry={() => { void currentCasesQuery.refetch(); void usersQuery.refetch(); void currentAdministrationUserQuery.refetch() }} /></div>
          ) : (
            <DataTable
              columns={columns}
              data={visibleCases}
              getRowId={(item) => item.id}
              isLoading={currentCasesQuery.isLoading || usersQuery.isLoading || currentAdministrationUserQuery.isLoading}
              emptyState={<EmptyState icon={ShieldCheck} title="Brak pasujących case’ów" description="Zmień filtry, aby zobaczyć aktywne sprawy zespołu." action={<Button variant="outline" onClick={clearFilters}>Wyczyść filtry</Button>} />}
            />
          )}
          {!currentCasesQuery.isLoading && <p className="text-right text-xs text-muted-foreground">Widocznych: {visibleCases.length} z {cases.length} aktywnych case’ów</p>}
        </div>
      </main>

      <Dialog open={Boolean(reassigned)} onOpenChange={(open) => !open && setReassigned(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Przepisz użytkownika</DialogTitle><DialogDescription>Wybierz nowego właściciela {reassigned?.reference}. Operacja zostanie zapisana w dzienniku aktywności case’a.</DialogDescription></DialogHeader>
          <Select value={assigneeId} onValueChange={(value) => setAssigneeId(String(value))}><SelectTrigger className="w-full"><SelectValue placeholder="Wybierz użytkownika" /></SelectTrigger><SelectContent>{agents.map((agent) => <SelectItem key={agent.id} value={agent.id}>{agent.fullName}</SelectItem>)}</SelectContent></Select>
          <Alert><ShieldCheck /><AlertDescription>Audyt zapisze poprzedniego i nowego właściciela, datę oraz wykonawcę operacji.</AlertDescription></Alert>
          <DialogFooter><DialogClose render={<Button variant="outline" />}>Anuluj</DialogClose><Button disabled={!assigneeId || actions.reassign.isPending} onClick={async () => { if (!reassigned) return; try { await actions.reassign.mutateAsync({ caseId: reassigned.id, userId: assigneeId }); notify.success("Przepisano użytkownika", reassigned.reference); setReassigned(null) } catch (error) { notify.error("Nie udało się przepisać użytkownika", getErrorMessage(error)) } }}>{actions.reassign.isPending ? "Zapisywanie…" : "Przepisz"}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog open={Boolean(unassigned)} onOpenChange={(open) => !open && setUnassigned(null)} title="Usunąć przypisanie?" description={unassigned ? `${unassigned.reference} wróci do puli nieprzypisanych. Zmiana zostanie zapisana w audycie i nie usuwa historii rozmowy.` : undefined} confirmLabel="Usuń przypisanie" destructive onConfirm={() => { if (!unassigned) return; const item = unassigned; void actions.unassign.mutateAsync(item.id).then(() => { notify.success("Usunięto przypisanie", item.reference); setUnassigned(null) }).catch((error) => notify.error("Nie udało się usunąć przypisania", getErrorMessage(error))) }} />
      <ConfirmDialog open={Boolean(resolved)} onOpenChange={(open) => !open && setResolved(null)} title="Wymusić rozwiązanie case’a?" description={resolved ? `${resolved.reference} zostanie zakończony administracyjnie, niezależnie od właściciela i bieżącego przepływu. Operacja będzie widoczna w audycie.` : undefined} confirmLabel="Wymuś rozwiązanie" destructive onConfirm={() => { if (!resolved) return; const item = resolved; void actions.forceResolve.mutateAsync(item.id).then(() => { notify.success("Case oznaczony jako rozwiązany", item.reference); setResolved(null) }).catch((error) => notify.error("Nie udało się zakończyć case’a", getErrorMessage(error))) }} />
    </>
  )
}

function SlaCell({ item }: { item: CurrentCaseAdminItem }) {
  return <div className="min-w-32"><SlaIndicator state={item.sla.state} dueAt={item.sla.dueAt} />{item.sla.dueAt && <p className="mt-1 text-xs text-muted-foreground">{formatRelative(item.sla.dueAt)}</p>}</div>
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Spróbuj ponownie."
}
