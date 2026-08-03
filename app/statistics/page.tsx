"use client"

import { useMemo, useState, type ReactNode } from "react"
import {
  Activity,
  BarChart3,
  CheckCircle2,
  Clock3,
  Gauge,
  Inbox,
  Info,
  RefreshCw,
  TimerReset,
} from "lucide-react"
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip as RechartsTooltip,
  XAxis,
  YAxis,
} from "recharts"
import { PageHeader } from "@/components/layout/page-header"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { EmptyState, ErrorState } from "@/components/design-system/data-states"
import { DashboardPageSkeleton } from "@/components/design-system/page-skeletons"
import {
  ALL_VALUE,
  FilterBar,
  FilterSelect,
} from "@/components/design-system/filter-bar"
import type { AnalyticsFilters, AnalyticsResult, UserPerformance } from "@/lib/domain/analytics"
import { channelLabels } from "@/lib/domain/labels"
import type { Channel } from "@/lib/domain/types"
import { formatDuration, formatNumber, formatPercent } from "@/lib/format"
import { useAnalytics } from "@/lib/services/queries"

type DatePreset = "today" | "7days" | "30days" | "custom"

const analyticsToday = "2026-08-03"
const chartTooltipStyle = {
  backgroundColor: "var(--popover)",
  border: "1px solid var(--border)",
  borderRadius: 8,
  color: "var(--popover-foreground)",
  fontSize: 12,
}
const platformColors: Record<Channel, string> = {
  slack: "var(--channel-slack)",
  teams: "var(--channel-teams)",
  telegram: "var(--channel-telegram)",
}

const metricDefinitions = {
  created: "Case’y utworzone w wybranym okresie i zgodne ze wszystkimi aktywnymi filtrami.",
  resolved: "Case’y z wybranego zbioru, które otrzymały status rozwiązany.",
  active: "Utworzone w okresie case’y, które nadal nie mają statusu rozwiązany.",
  sla: "Odsetek case’ów, w których pierwsza odpowiedź została udzielona przed terminem SLA.",
  claim: "Średni czas od utworzenia case’a do przypisania pierwszego właściciela.",
  response: "Średni czas od utworzenia case’a do pierwszej wiadomości wysłanej przez wsparcie.",
  asked: "Liczba case’ów, w których użytkownik wysłał do klienta prośbę o dodatkowe informacje.",
  ignore: "Suma punktów głosów ignorowania oddanych przez użytkownika w wybranym okresie.",
} as const

export default function StatisticsRoute() {
  const [preset, setPreset] = useState<DatePreset>("7days")
  const [customFrom, setCustomFrom] = useState("2026-07-28")
  const [customTo, setCustomTo] = useState(analyticsToday)
  const [user, setUser] = useState(ALL_VALUE)
  const [customer, setCustomer] = useState(ALL_VALUE)
  const [platform, setPlatform] = useState(ALL_VALUE)
  const [sourceChannel, setSourceChannel] = useState(ALL_VALUE)

  const dateRange = useMemo(
    () => preset === "custom" ? { from: customFrom, to: customTo } : getPresetRange(preset),
    [customFrom, customTo, preset],
  )
  const filters = useMemo<AnalyticsFilters>(
    () => ({
      dateFrom: dateRange.from,
      dateTo: dateRange.to,
      userId: user === ALL_VALUE ? undefined : user,
      customerId: customer === ALL_VALUE ? undefined : customer,
      platform: platform === ALL_VALUE ? undefined : (platform as Channel),
      sourceChannel: sourceChannel === ALL_VALUE ? undefined : sourceChannel,
    }),
    [customer, dateRange.from, dateRange.to, platform, sourceChannel, user],
  )
  const analyticsQuery = useAnalytics(filters)
  const analytics = analyticsQuery.data

  const resetFilters = () => {
    setPreset("7days")
    setUser(ALL_VALUE)
    setCustomer(ALL_VALUE)
    setPlatform(ALL_VALUE)
    setSourceChannel(ALL_VALUE)
  }

  return (
    <TooltipProvider>
      <PageHeader
        title="Statystyki"
        description="Wyniki operacyjne, SLA i efektywność zespołu wsparcia"
        actions={<Button variant="outline" size="sm" onClick={() => void analyticsQuery.refetch()} disabled={analyticsQuery.isFetching}><RefreshCw className={analyticsQuery.isFetching ? "animate-spin" : undefined} /> Odśwież</Button>}
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6">
          <Card size="sm">
            <CardContent className="grid gap-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="mr-1 text-xs font-medium text-muted-foreground">Zakres dat</span>
                {([
                  ["today", "Dzisiaj"],
                  ["7days", "7 dni"],
                  ["30days", "30 dni"],
                  ["custom", "Własny zakres"],
                ] as [DatePreset, string][]).map(([value, label]) => (
                  <Button key={value} size="sm" variant={preset === value ? "default" : "outline"} onClick={() => setPreset(value)}>{label}</Button>
                ))}
                {preset === "custom" && <div className="flex flex-wrap items-center gap-2 sm:ml-2"><Input type="date" value={customFrom} max={customTo} onChange={(event) => setCustomFrom(event.target.value)} className="w-40" aria-label="Data od" /><span className="text-xs text-muted-foreground">–</span><Input type="date" value={customTo} min={customFrom} onChange={(event) => setCustomTo(event.target.value)} className="w-40" aria-label="Data do" /></div>}
              </div>
              <FilterBar className="border-t pt-3">
                <FilterSelect value={user} onChange={setUser} placeholder="Użytkownik" allLabel="Wszyscy użytkownicy" options={(analytics?.options.users ?? []).map((item) => ({ value: item.id, label: item.fullName }))} />
                <FilterSelect value={customer} onChange={setCustomer} placeholder="Klient" allLabel="Wszyscy klienci" options={(analytics?.options.customers ?? []).map((item) => ({ value: item.id, label: item.name }))} />
                <FilterSelect value={platform} onChange={setPlatform} placeholder="Platforma" allLabel="Wszystkie platformy" options={(Object.entries(channelLabels) as [Channel, string][]).map(([value, label]) => ({ value, label }))} />
                <FilterSelect value={sourceChannel} onChange={setSourceChannel} placeholder="Kanał" allLabel="Wszystkie kanały" options={(analytics?.options.channels ?? []).filter((item) => platform === ALL_VALUE || item.platform === platform).map((item) => ({ value: item.value, label: `${channelLabels[item.platform]} · ${item.label}` }))} className="sm:w-60" />
                {(preset !== "7days" || user !== ALL_VALUE || customer !== ALL_VALUE || platform !== ALL_VALUE || sourceChannel !== ALL_VALUE) && <Button variant="ghost" size="sm" onClick={resetFilters}>Wyczyść filtry</Button>}
                <Badge variant="secondary" className="sm:ml-auto">{formatRange(dateRange.from, dateRange.to)}</Badge>
              </FilterBar>
            </CardContent>
          </Card>

          {analyticsQuery.isLoading ? (
            <DashboardPageSkeleton />
          ) : analyticsQuery.isError || !analytics ? (
            <div className="rounded-lg border bg-card"><ErrorState onRetry={() => void analyticsQuery.refetch()} /></div>
          ) : analytics.kpis.created === 0 ? (
            <div className="rounded-lg border bg-card"><EmptyState icon={BarChart3} title="Brak danych dla wybranego zakresu" description="Zmień daty lub filtry, aby zobaczyć statystyki." action={<Button variant="outline" onClick={resetFilters}>Pokaż ostatnie 7 dni</Button>} /></div>
          ) : (
            <AnalyticsDashboard data={analytics} />
          )}
        </div>
      </main>
    </TooltipProvider>
  )
}

function AnalyticsDashboard({ data }: { data: AnalyticsResult }) {
  return (
    <div className="grid gap-4">
      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6" aria-label="Najważniejsze wskaźniki">
        <MetricCard label="Utworzone" value={formatNumber(data.kpis.created)} icon={Inbox} definition={metricDefinitions.created} tone="primary" />
        <MetricCard label="Rozwiązane" value={formatNumber(data.kpis.resolved)} icon={CheckCircle2} definition={metricDefinitions.resolved} tone="success" />
        <MetricCard label="Aktywne" value={formatNumber(data.kpis.active)} icon={Activity} definition={metricDefinitions.active} tone="info" />
        <MetricCard label="SLA spełnione" value={formatPercent(data.kpis.slaMetPercentage)} icon={Gauge} definition={metricDefinitions.sla} tone="success" />
        <MetricCard label="Śr. czas przejęcia" value={formatDuration(data.kpis.averageClaimMinutes)} icon={Clock3} definition={metricDefinitions.claim} tone="warning" />
        <MetricCard label="Śr. pierwsza odpowiedź" value={formatDuration(data.kpis.averageFirstResponseMinutes)} icon={TimerReset} definition={metricDefinitions.response} tone="teams" />
      </section>

      <section className="grid gap-4 xl:grid-cols-[minmax(0,1.55fr)_minmax(340px,0.8fr)]">
        <ChartCard title="Utworzone i rozwiązane w czasie" description="Dzienna liczba case’ów zgodnych z aktywnymi filtrami.">
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <LineChart data={data.timeSeries} margin={{ top: 8, right: 12, bottom: 0, left: -12 }} accessibilityLayer>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="date" tickFormatter={formatShortDate} tick={{ fill: "var(--muted-foreground)", fontSize: 11 }} tickLine={false} axisLine={false} minTickGap={20} />
              <YAxis allowDecimals={false} tick={{ fill: "var(--muted-foreground)", fontSize: 11 }} tickLine={false} axisLine={false} width={36} />
              <RechartsTooltip contentStyle={chartTooltipStyle} labelFormatter={formatLongDate} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="created" name="Utworzone" stroke="var(--primary)" strokeWidth={2.5} dot={false} activeDot={{ r: 4 }} />
              <Line type="monotone" dataKey="resolved" name="Rozwiązane" stroke="var(--success)" strokeWidth={2.5} dot={false} activeDot={{ r: 4 }} />
            </LineChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Rozkład źródeł" description="Udział platform w liczbie utworzonych case’ów.">
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <PieChart accessibilityLayer>
              <Pie data={data.sourceDistribution.map((item) => ({ ...item, name: channelLabels[item.platform] }))} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="82%" paddingAngle={3} stroke="var(--card)" strokeWidth={2}>
                {data.sourceDistribution.map((item) => <Cell key={item.platform} fill={platformColors[item.platform]} />)}
              </Pie>
              <RechartsTooltip contentStyle={chartTooltipStyle} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        </ChartCard>
      </section>

      <section className="grid gap-4 lg:grid-cols-2">
        <ChartCard title="SLA spełnione i przekroczone" description="Ocena terminu pierwszej odpowiedzi dla każdego case’a.">
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <PieChart accessibilityLayer>
              <Pie data={data.slaDistribution.map((item) => ({ ...item, name: item.state === "met" ? "Spełnione" : "Przekroczone" }))} dataKey="value" nameKey="name" innerRadius="52%" outerRadius="80%" paddingAngle={3}>
                <Cell fill="var(--success)" /><Cell fill="var(--destructive)" />
              </Pie>
              <RechartsTooltip contentStyle={chartTooltipStyle} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Średnia odpowiedź według użytkownika" description="Minuty od utworzenia case’a do pierwszej odpowiedzi wsparcia.">
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <BarChart data={data.responseByUser} layout="vertical" margin={{ top: 8, right: 16, bottom: 0, left: 12 }} accessibilityLayer>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" horizontal={false} />
              <XAxis type="number" tick={{ fill: "var(--muted-foreground)", fontSize: 11 }} tickLine={false} axisLine={false} unit=" min" />
              <YAxis type="category" dataKey="userName" tick={{ fill: "var(--muted-foreground)", fontSize: 11 }} tickLine={false} axisLine={false} width={112} />
              <RechartsTooltip contentStyle={chartTooltipStyle} />
              <Bar dataKey="minutes" name="Średnia odpowiedź" fill="var(--channel-teams)" radius={[0, 5, 5, 0]} maxBarSize={26} />
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>
      </section>

      <PerformanceTable data={data.userPerformance} />
    </div>
  )
}

function PerformanceTable({ data }: { data: UserPerformance[] }) {
  return (
    <Card>
      <CardHeader><CardTitle>Wyniki użytkowników</CardTitle><CardDescription>Porównanie aktywności i jakości obsługi w wybranym okresie.</CardDescription></CardHeader>
      <CardContent>
        <div className="overflow-hidden rounded-lg border">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>Użytkownik</TableHead><TableHead className="text-center"><MetricLabel label="Przejęte" definition={metricDefinitions.claim} /></TableHead><TableHead className="text-center"><MetricLabel label="Rozwiązane" definition={metricDefinitions.resolved} /></TableHead><TableHead className="text-center"><MetricLabel label="Dopytane" definition={metricDefinitions.asked} /></TableHead><TableHead className="text-center"><MetricLabel label="Głosy ignorowania" definition={metricDefinitions.ignore} /></TableHead><TableHead className="text-right"><MetricLabel label="Śr. przejęcie" definition={metricDefinitions.claim} /></TableHead><TableHead className="text-right"><MetricLabel label="Śr. odpowiedź" definition={metricDefinitions.response} /></TableHead><TableHead className="text-right"><MetricLabel label="SLA" definition={metricDefinitions.sla} /></TableHead></TableRow></TableHeader>
            <TableBody>
              {data.map((item) => (
                <TableRow key={item.userId}>
                  <TableCell className="font-medium">{item.userName}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.claimed}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.resolved}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.askedForInformation}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.ignoreVotes}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.averageClaimMinutes)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.averageResponseMinutes)}</TableCell>
                  <TableCell className="text-right"><Badge variant="secondary" className={item.slaPercentage >= 0.85 ? "bg-success/10 text-success" : item.slaPercentage >= 0.7 ? "bg-warning/15 text-warning-foreground" : "bg-destructive/10 text-destructive"}>{item.slaPercentage >= 0.85 ? <CheckCircle2 /> : <Gauge />}{formatPercent(item.slaPercentage)}</Badge></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

function MetricCard({ label, value, icon: Icon, definition, tone }: { label: string; value: string; icon: typeof Inbox; definition: string; tone: "primary" | "success" | "info" | "warning" | "teams" }) {
  const tones = { primary: "bg-primary/10 text-primary", success: "bg-success/10 text-success", info: "bg-info/10 text-info", warning: "bg-warning/15 text-warning-foreground", teams: "bg-channel-teams/10 text-channel-teams" }
  return <Card size="sm" className="min-w-0"><CardContent className="flex items-start justify-between gap-2"><div className="min-w-0"><MetricLabel label={label} definition={definition} /><p className="mt-1 truncate text-xl font-semibold tracking-tight tabular-nums">{value}</p></div><span className={`flex size-8 shrink-0 items-center justify-center rounded-lg ${tones[tone]}`}><Icon className="size-4" /></span></CardContent></Card>
}

function MetricLabel({ label, definition }: { label: string; definition: string }) {
  return <span className="inline-flex items-center gap-1 text-xs text-muted-foreground"><span>{label}</span><Tooltip><TooltipTrigger render={<button type="button" className="rounded-sm text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label={`Definicja: ${label}`} />}><Info className="size-3.5" /></TooltipTrigger><TooltipContent>{definition}</TooltipContent></Tooltip></span>
}

function ChartCard({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <Card className="min-w-0"><CardHeader><CardTitle>{title}</CardTitle><CardDescription>{description}</CardDescription></CardHeader><CardContent><div className="h-[280px] w-full min-w-0">{children}</div></CardContent></Card>
}

function getPresetRange(preset: Exclude<DatePreset, "custom">) {
  const days = preset === "today" ? 1 : preset === "7days" ? 7 : 30
  const end = new Date(`${analyticsToday}T12:00:00.000Z`)
  const start = new Date(end)
  start.setUTCDate(start.getUTCDate() - (days - 1))
  return { from: start.toISOString().slice(0, 10), to: analyticsToday }
}

function formatShortDate(value: string) {
  return new Intl.DateTimeFormat("pl-PL", { day: "2-digit", month: "2-digit" }).format(new Date(`${value}T12:00:00.000Z`))
}

function formatLongDate(value: ReactNode) {
  if (typeof value !== "string") return String(value ?? "")
  return new Intl.DateTimeFormat("pl-PL", { day: "numeric", month: "long", year: "numeric" }).format(new Date(`${value}T12:00:00.000Z`))
}

function formatRange(from: string, to: string) {
  return `${formatShortDate(from)} – ${formatShortDate(to)}`
}
