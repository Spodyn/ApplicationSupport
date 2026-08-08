"use client"

import { useEffect, useMemo, useState, type ReactNode } from "react"
import {
  Activity,
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  BarChart3,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Download,
  Filter,
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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { EmptyState, ErrorState } from "@/components/design-system/data-states"
import { ALL_VALUE, FilterBar } from "@/components/design-system/filter-bar"
import { notify } from "@/components/design-system/notify"
import { DashboardPageSkeleton } from "@/components/design-system/page-skeletons"
import type {
  AnalyticsFilters,
  AnalyticsKpis,
  AnalyticsResult,
  BacklogAgePoint,
  ClientPerformance,
  DistributionPoint,
  HeatmapPoint,
  UserPerformance,
} from "@/lib/domain/analytics"
import { channelLabels } from "@/lib/domain/labels"
import type { Channel } from "@/lib/domain/types"
import { formatDuration, formatNumber, formatPercent } from "@/lib/format"
import { useAnalytics } from "@/lib/services/queries"
import { cn } from "@/lib/utils"

type DatePreset =
  | "today"
  | "yesterday"
  | "7days"
  | "30days"
  | "90days"
  | "this_month"
  | "previous_month"
  | "custom"

const analyticsToday = new Date().toISOString().slice(0, 10)
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
const statusColors = ["var(--primary)", "var(--channel-teams)", "var(--info)", "var(--warning)", "#8b5cf6", "var(--success)", "#64748b"]
const slaColors = {
  met: "var(--success)",
  warning: "var(--warning)",
  breached: "var(--destructive)",
}

const metricDefinitions = {
  created: "Case’y utworzone w wybranym okresie i zgodne ze wszystkimi aktywnymi filtrami.",
  resolved: "Case’y rozwiązane w wybranym okresie, niezależnie od daty ich utworzenia.",
  backlog: "Liczba nierozwiązanych case’ów aktywnych na koniec analizowanego okresu.",
  sla: "Odsetek case’ów, w których pierwsza odpowiedź wsparcia została wysłana przed terminem SLA.",
  breached: "Liczba case’ów utworzonych w okresie, dla których SLA pierwszej odpowiedzi zostało przekroczone.",
  claim: "Średni czas od utworzenia case’a do przypisania pierwszego właściciela.",
  response: "Średni czas od utworzenia case’a do pierwszej wiadomości wysłanej przez wsparcie.",
  median: "Połowa pierwszych odpowiedzi została wysłana w czasie równym lub krótszym od tej wartości.",
  p90: "90% pierwszych odpowiedzi zostało wysłanych w czasie równym lub krótszym od tej wartości.",
  resolution: "Średni czas od utworzenia case’a do jego finalnego zamknięcia.",
} as const

const presets: [DatePreset, string][] = [
  ["today", "Dziś"],
  ["yesterday", "Wczoraj"],
  ["7days", "7 dni"],
  ["30days", "30 dni"],
  ["90days", "90 dni"],
  ["this_month", "Ten miesiąc"],
  ["previous_month", "Poprzedni miesiąc"],
  ["custom", "Własny zakres"],
]

export default function StatisticsRoute() {
  const initialRange = getPresetRange("7days")
  const [preset, setPreset] = useState<DatePreset>("7days")
  const [customFrom, setCustomFrom] = useState(initialRange.from)
  const [customTo, setCustomTo] = useState(initialRange.to)
  const [comparePrevious, setComparePrevious] = useState(true)
  const [user, setUser] = useState(ALL_VALUE)
  const [customer, setCustomer] = useState(ALL_VALUE)
  const [platform, setPlatform] = useState(ALL_VALUE)
  const [sourceChannel, setSourceChannel] = useState(ALL_VALUE)
  const [status, setStatus] = useState(ALL_VALUE)
  const [slaState, setSlaState] = useState(ALL_VALUE)
  const [clock, setClock] = useState(() => Date.now())

  useEffect(() => {
    const timer = window.setInterval(() => setClock(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

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
      status: status === ALL_VALUE ? undefined : status,
      slaState: slaState === ALL_VALUE ? undefined : slaState as AnalyticsFilters["slaState"],
      comparePrevious,
    }),
    [comparePrevious, customer, dateRange.from, dateRange.to, platform, slaState, sourceChannel, status, user],
  )
  const analyticsQuery = useAnalytics(filters)
  const analytics = analyticsQuery.data
  const updatedSecondsAgo = analyticsQuery.dataUpdatedAt
    ? Math.max(0, Math.floor((clock - analyticsQuery.dataUpdatedAt) / 1_000))
    : 0

  const resetFilters = () => {
    setPreset("7days")
    setComparePrevious(true)
    setUser(ALL_VALUE)
    setCustomer(ALL_VALUE)
    setPlatform(ALL_VALUE)
    setSourceChannel(ALL_VALUE)
    setStatus(ALL_VALUE)
    setSlaState(ALL_VALUE)
  }

  const filtersChanged = preset !== "7days" || !comparePrevious || [user, customer, platform, sourceChannel, status, slaState].some((value) => value !== ALL_VALUE)

  return (
    <TooltipProvider>
      <PageHeader
        title="Statystyki"
        description="Wyniki operacyjne, SLA i efektywność zespołu wsparcia"
        actions={(
          <>
            <div className="mr-1 hidden items-center gap-2 text-xs text-muted-foreground xl:flex">
              <span className={cn("size-2 rounded-full bg-success", analyticsQuery.isFetching && "animate-pulse")} />
              <span>Dane na żywo · {analyticsQuery.isFetching ? "aktualizacja…" : `aktualizacja ${updatedSecondsAgo} s temu`}</span>
            </div>
            <Button variant="outline" size="sm" onClick={() => void analyticsQuery.refetch()} disabled={analyticsQuery.isFetching}>
              <RefreshCw className={analyticsQuery.isFetching ? "animate-spin" : undefined} /> Odśwież
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger render={<Button variant="outline" size="sm"><Download /> Eksportuj <ChevronDown /></Button>} />
              <DropdownMenuContent align="end" className="min-w-40">
                <DropdownMenuItem onClick={() => notify.info("Eksport CSV", "Eksport zostanie podłączony do endpointu analitycznego.")}>CSV</DropdownMenuItem>
                <DropdownMenuItem onClick={() => notify.info("Eksport XLSX", "Eksport zostanie podłączony do endpointu analitycznego.")}>XLSX</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        )}
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1760px] flex-col gap-4 p-4 md:p-6">
          <Card size="sm">
            <CardContent className="grid gap-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="mr-1 text-xs font-medium text-muted-foreground">Zakres czasu</span>
                {presets.map(([value, label]) => (
                  <Button key={value} size="sm" variant={preset === value ? "default" : "outline"} onClick={() => setPreset(value)}>{label}</Button>
                ))}
                <label className="ml-auto flex items-center gap-2.5 rounded-lg border bg-muted/20 px-3 py-2 text-xs font-medium">
                  <Switch checked={comparePrevious} onCheckedChange={setComparePrevious} aria-label="Porównaj z poprzednim okresem" />
                  Porównaj z poprzednim okresem
                </label>
              </div>

              {preset === "custom" && (
                <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-muted/20 p-3">
                  <label className="grid gap-1.5 text-xs font-medium text-muted-foreground">
                    Data od
                    <Input
                      type="date"
                      value={customFrom}
                      max={analyticsToday}
                      onChange={(event) => {
                        const value = event.target.value
                        setCustomFrom(value)
                        if (value > customTo) setCustomTo(value)
                      }}
                      className="w-44 bg-card text-foreground"
                    />
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-muted-foreground">
                    Data do
                    <Input
                      type="date"
                      value={customTo}
                      min={customFrom}
                      max={analyticsToday}
                      onChange={(event) => {
                        const value = event.target.value
                        setCustomTo(value)
                        if (value < customFrom) setCustomFrom(value)
                      }}
                      className="w-44 bg-card text-foreground"
                    />
                  </label>
                  <span className="pb-2 text-xs text-muted-foreground">Zakres nie może obejmować przyszłych dat.</span>
                </div>
              )}

              <FilterBar className="border-t pt-3">
                <DashboardFilterSelect value={user} onChange={setUser} label="Użytkownik" allLabel="Wszyscy użytkownicy" options={(analytics?.options.users ?? []).map((item) => ({ value: item.id, label: item.fullName }))} />
                <DashboardFilterSelect value={customer} onChange={setCustomer} label="Klient" allLabel="Wszyscy klienci" options={(analytics?.options.customers ?? []).map((item) => ({ value: item.id, label: item.name }))} />
                <DashboardFilterSelect value={platform} onChange={(value) => { setPlatform(value); setSourceChannel(ALL_VALUE) }} label="Platforma" allLabel="Wszystkie platformy" options={(Object.entries(channelLabels) as [Channel, string][]).map(([value, label]) => ({ value, label }))} />
                <DashboardFilterSelect value={sourceChannel} onChange={setSourceChannel} label="Kanał" allLabel="Wszystkie kanały" options={(analytics?.options.channels ?? []).filter((item) => platform === ALL_VALUE || item.platform === platform).map((item) => ({ value: item.value, label: item.label }))} />
                <DashboardFilterSelect value={status} onChange={setStatus} label="Status" allLabel="Wszystkie statusy" options={analytics?.options.statuses ?? []} />
                <DashboardFilterSelect value={slaState} onChange={setSlaState} label="SLA" allLabel="Wszystkie SLA" options={(analytics?.options.slaStates ?? []).map((item) => ({ value: item.value, label: item.label }))} />
                <Button variant="outline" size="sm" onClick={() => notify.info("Więcej filtrów", "Struktura obsługuje już priority, tag i team; ich UI zostanie dodane wraz z backendem.")}><Filter /> Więcej filtrów</Button>
                {filtersChanged && <Button variant="ghost" size="sm" onClick={resetFilters}>Wyczyść filtry</Button>}
                <Badge variant="secondary" className="sm:ml-auto">{formatRange(dateRange.from, dateRange.to)}</Badge>
              </FilterBar>
            </CardContent>
          </Card>

          {analyticsQuery.isLoading ? (
            <DashboardPageSkeleton />
          ) : analyticsQuery.isError || !analytics ? (
            <div className="rounded-lg border bg-card"><ErrorState onRetry={() => void analyticsQuery.refetch()} /></div>
          ) : analytics.kpis.created === 0 && analytics.kpis.resolved === 0 ? (
            <div className="rounded-lg border bg-card"><EmptyState icon={BarChart3} title="Brak danych dla wybranego zakresu" description="Zmień daty lub filtry, aby zobaczyć statystyki." action={<Button variant="outline" onClick={resetFilters}>Pokaż ostatnie 7 dni</Button>} /></div>
          ) : (
            <AnalyticsDashboard data={analytics} preset={preset} />
          )}
        </div>
      </main>
    </TooltipProvider>
  )
}

function AnalyticsDashboard({ data, preset }: { data: AnalyticsResult; preset: DatePreset }) {
  const drillDown = (label: string) => notify.info("Drill-down przygotowany", `${label} będzie otwierać przefiltrowaną listę /cases po podłączeniu obsługi query params.`)
  const comparisonLabel = getComparisonLabel(preset, data.comparison?.label)
  const metricCards: Array<{
    metricKey: keyof AnalyticsKpis
    label: string
    value: string
    icon: typeof Inbox
    tone: MetricTone
    definition: string
    lowerIsBetter?: boolean
    percentagePoints?: boolean
  }> = [
    { metricKey: "created", label: "Utworzone", value: formatNumber(data.kpis.created), icon: Inbox, tone: "primary", definition: metricDefinitions.created },
    { metricKey: "resolved", label: "Rozwiązane", value: formatNumber(data.kpis.resolved), icon: CheckCircle2, tone: "success", definition: metricDefinitions.resolved },
    { metricKey: "backlog", label: "Aktualny backlog", value: formatNumber(data.kpis.backlog), icon: Activity, tone: "info", definition: metricDefinitions.backlog, lowerIsBetter: true },
    { metricKey: "slaFirstResponsePercentage", label: "SLA – pierwsza odpowiedź", value: formatPercent(data.kpis.slaFirstResponsePercentage), icon: Gauge, tone: "success", definition: metricDefinitions.sla, percentagePoints: true },
    { metricKey: "breachedSla", label: "Przekroczone SLA", value: formatNumber(data.kpis.breachedSla), icon: AlertTriangle, tone: "danger", definition: metricDefinitions.breached, lowerIsBetter: true },
    { metricKey: "averageClaimMinutes", label: "Śr. czas przejęcia", value: formatDuration(data.kpis.averageClaimMinutes), icon: Clock3, tone: "warning", definition: metricDefinitions.claim, lowerIsBetter: true },
    { metricKey: "averageFirstResponseMinutes", label: "Śr. pierwsza odpowiedź", value: formatDuration(data.kpis.averageFirstResponseMinutes), icon: TimerReset, tone: "teams", definition: metricDefinitions.response, lowerIsBetter: true },
    { metricKey: "averageResolutionMinutes", label: "Śr. czas rozwiązania", value: formatDuration(data.kpis.averageResolutionMinutes), icon: CheckCircle2, tone: "neutral", definition: metricDefinitions.resolution, lowerIsBetter: true },
  ]

  return (
    <div className="grid gap-4">
      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4 min-[1800px]:grid-cols-8!" aria-label="Najważniejsze wskaźniki">
        {metricCards.map(({ metricKey, ...item }) => (
          <MetricCard
            key={metricKey}
            {...item}
            current={data.kpis[metricKey]}
            previous={data.comparison?.kpis[metricKey]}
            comparisonLabel={comparisonLabel}
            onClick={() => drillDown(item.label)}
          />
        ))}
      </section>

      <section className="grid gap-4 xl:grid-cols-[minmax(0,1.55fr)_minmax(360px,0.8fr)]">
        <ChartCard title="Utworzone, rozwiązane i backlog w czasie" description="Wolumen spraw oraz liczba aktywnych na koniec każdego dnia." height={310}>
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <LineChart data={data.timeSeries} margin={{ top: 8, right: 12, bottom: 0, left: -12 }} accessibilityLayer>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="date" tickFormatter={formatShortDate} tick={axisTick} tickLine={false} axisLine={false} minTickGap={20} />
              <YAxis allowDecimals={false} tick={axisTick} tickLine={false} axisLine={false} width={38} />
              <RechartsTooltip contentStyle={chartTooltipStyle} labelFormatter={formatLongDate} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="created" name="Utworzone" stroke="var(--primary)" strokeWidth={2.4} dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
              <Line type="monotone" dataKey="resolved" name="Rozwiązane" stroke="var(--success)" strokeWidth={2.4} dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
              <Line type="monotone" dataKey="backlog" name="Backlog" stroke="var(--warning)" strokeWidth={2.2} strokeDasharray="5 4" dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="SLA – pierwsza odpowiedź w czasie" description="Proporcje spełnionych, zagrożonych i przekroczonych terminów." height={310}>
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <BarChart data={data.slaTimeSeries} stackOffset="expand" margin={{ top: 8, right: 8, bottom: 0, left: -12 }} accessibilityLayer>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="date" tickFormatter={formatShortDate} tick={axisTick} tickLine={false} axisLine={false} minTickGap={22} />
              <YAxis tickFormatter={(value: number) => `${Math.round(value * 100)}%`} tick={axisTick} tickLine={false} axisLine={false} width={42} />
              <RechartsTooltip contentStyle={chartTooltipStyle} labelFormatter={formatLongDate} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
              <Bar dataKey="met" name="Spełnione" stackId="sla" fill={slaColors.met} isAnimationActive={false} />
              <Bar dataKey="warning" name="Zagrożone" stackId="sla" fill={slaColors.warning} isAnimationActive={false} />
              <Bar dataKey="breached" name="Przekroczone" stackId="sla" fill={slaColors.breached} radius={[3, 3, 0, 0]} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>
      </section>

      <section className="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
        <DistributionCard title="Źródła case’ów" description="Udział platform w utworzonych sprawach." data={data.sourceDistribution} colors={data.sourceDistribution.map((item) => platformColors[item.state])} onSelect={(value) => drillDown(`Platforma: ${value}`)} />
        <DistributionCard title="Statusy case’ów" description="Dynamiczny rozkład statusów biznesowych." data={data.statusDistribution} colors={data.statusDistribution.map((_, index) => statusColors[index % statusColors.length])} onSelect={(value) => drillDown(`Status: ${value}`)} />
        <DistributionCard title="SLA – podsumowanie" description="Stan SLA pierwszej odpowiedzi." data={data.slaDistribution} colors={data.slaDistribution.map((item) => slaColors[item.state])} onSelect={(value) => drillDown(`SLA: ${value}`)} className="lg:col-span-2 2xl:col-span-1" />
      </section>

      <section className="grid gap-4 xl:grid-cols-[minmax(340px,0.72fr)_minmax(0,1.45fr)]">
        <BacklogAgeCard data={data.backlogAgeDistribution} onSelect={(label) => drillDown(`Wiek backlogu: ${label}`)} />
        <ClientPerformanceTable data={data.clientPerformance} onSelect={(label) => drillDown(`Klient: ${label}`)} />
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <ChartCard title="Średnia pierwsza odpowiedź w czasie" description="Trend średniej, mediany i P90 czasu pierwszej odpowiedzi." height={300}>
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <LineChart data={data.responseTimeSeries} margin={{ top: 8, right: 12, bottom: 0, left: -8 }} accessibilityLayer>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="date" tickFormatter={formatShortDate} tick={axisTick} tickLine={false} axisLine={false} minTickGap={20} />
              <YAxis unit=" min" tick={axisTick} tickLine={false} axisLine={false} width={52} />
              <RechartsTooltip contentStyle={chartTooltipStyle} labelFormatter={formatLongDate} formatter={(value) => [`${Math.round(Number(value ?? 0))} min`, "Czas"]} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="averageMinutes" name="Średnia" stroke="var(--primary)" strokeWidth={2.4} dot={false} isAnimationActive={false} />
              <Line type="monotone" dataKey="medianMinutes" name="Mediana / P50" stroke="var(--success)" strokeWidth={2} dot={false} isAnimationActive={false} />
              <Line type="monotone" dataKey="p90Minutes" name="P90" stroke="var(--warning)" strokeWidth={2} strokeDasharray="5 4" dot={false} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </ChartCard>
        <HeatmapCard data={data.heatmap} />
      </section>

      <PerformanceTable data={data.userPerformance} />
    </div>
  )
}

type MetricTone = "primary" | "success" | "info" | "warning" | "teams" | "danger" | "neutral"

function MetricCard({
  label,
  value,
  icon: Icon,
  definition,
  tone,
  current,
  previous,
  lowerIsBetter = false,
  percentagePoints = false,
  comparisonLabel,
  onClick,
}: {
  label: string
  value: string
  icon: typeof Inbox
  definition: string
  tone: MetricTone
  current: number
  previous?: number
  lowerIsBetter?: boolean
  percentagePoints?: boolean
  comparisonLabel: string
  onClick: () => void
}) {
  const tones: Record<MetricTone, string> = {
    primary: "bg-primary/10 text-primary",
    success: "bg-success/10 text-success",
    info: "bg-info/10 text-info",
    warning: "bg-warning/15 text-warning-foreground",
    teams: "bg-channel-teams/10 text-channel-teams",
    danger: "bg-destructive/10 text-destructive",
    neutral: "bg-muted text-muted-foreground",
  }
  return (
    <Card size="sm" className="min-w-0 transition-colors hover:border-primary/35">
      <button type="button" onClick={onClick} className="w-full px-3 text-left">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0"><MetricLabel label={label} definition={definition} /><p className="mt-1 truncate text-xl font-semibold tracking-tight tabular-nums">{value}</p></div>
          <span className={cn("flex size-8 shrink-0 items-center justify-center rounded-lg", tones[tone])}><Icon className="size-4" /></span>
        </div>
        {previous !== undefined && <KpiDelta current={current} previous={previous} lowerIsBetter={lowerIsBetter} percentagePoints={percentagePoints} label={comparisonLabel} />}
      </button>
    </Card>
  )
}

function KpiDelta({ current, previous, lowerIsBetter, percentagePoints, label }: { current: number; previous: number; lowerIsBetter: boolean; percentagePoints: boolean; label: string }) {
  const rawDelta = current - previous
  const delta = percentagePoints ? rawDelta * 100 : previous ? rawDelta / previous * 100 : undefined
  if (delta === undefined) return <p className="mt-2 text-[10px] text-muted-foreground">Brak wartości w poprzednim okresie</p>
  const improved = lowerIsBetter ? delta < 0 : delta > 0
  const unchanged = Math.abs(delta) < 0.05
  const Icon = delta >= 0 ? ArrowUp : ArrowDown
  return (
    <p className="mt-2 flex flex-wrap items-center gap-x-1 text-[10px]">
      <span className={cn("inline-flex items-center font-semibold tabular-nums", unchanged ? "text-muted-foreground" : improved ? "text-success" : "text-destructive")}><Icon className="size-3" />{Math.abs(delta).toLocaleString("pl-PL", { maximumFractionDigits: 1 })}{percentagePoints ? " p.p." : "%"}</span>
      <span className="truncate text-muted-foreground">{label}</span>
    </p>
  )
}

function MetricLabel({ label, definition }: { label: string; definition: string }) {
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
      <span>{label}</span>
      <Tooltip>
        <TooltipTrigger render={<span role="button" tabIndex={0} className="rounded-sm text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label={`Definicja: ${label}`} />}><Info className="size-3.5" /></TooltipTrigger>
        <TooltipContent className="max-w-72">{definition}</TooltipContent>
      </Tooltip>
    </span>
  )
}

function ChartCard({ title, description, children, height = 280 }: { title: string; description: string; children: ReactNode; height?: number }) {
  return <Card className="min-w-0"><CardHeader><CardTitle>{title}</CardTitle><CardDescription>{description}</CardDescription></CardHeader><CardContent><div className="w-full min-w-0" style={{ height }}>{children}</div></CardContent></Card>
}

function DistributionCard({ title, description, data, colors, onSelect, className }: { title: string; description: string; data: DistributionPoint[]; colors: string[]; onSelect: (state: string) => void; className?: string }) {
  return (
    <Card className={cn("min-w-0", className)}>
      <CardHeader><CardTitle>{title}</CardTitle><CardDescription>{description}</CardDescription></CardHeader>
      <CardContent className="grid min-h-[245px] grid-cols-[minmax(130px,0.9fr)_minmax(150px,1.1fr)] items-center gap-2">
        <div className="h-[210px] min-w-0">
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <PieChart accessibilityLayer>
              <Pie data={data} dataKey="value" nameKey="label" innerRadius="57%" outerRadius="83%" paddingAngle={2} stroke="var(--card)" strokeWidth={2} isAnimationActive={false}>
                {data.map((item, index) => <Cell key={item.state} fill={colors[index]} />)}
              </Pie>
              <RechartsTooltip contentStyle={chartTooltipStyle} formatter={(value) => [formatNumber(Number(value ?? 0)), "Case’y"]} />
            </PieChart>
          </ResponsiveContainer>
        </div>
        <div className="grid content-center gap-2">
          {data.map((item, index) => (
            <button key={item.state} type="button" onClick={() => onSelect(item.state)} className="flex items-center gap-2 rounded-md p-1 text-left text-xs hover:bg-muted/60">
              <span className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: colors[index] }} />
              <span className="min-w-0 flex-1 truncate">{item.label}</span>
              <span className="tabular-nums text-muted-foreground">{formatNumber(item.value)}</span>
              <span className="w-11 text-right font-medium tabular-nums">{formatPercent(item.percentage)}</span>
            </button>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function BacklogAgeCard({ data, onSelect }: { data: BacklogAgePoint[]; onSelect: (label: string) => void }) {
  const max = Math.max(...data.map((item) => item.value), 1)
  return (
    <Card>
      <CardHeader><CardTitle>Wiek aktywnych zgłoszeń</CardTitle><CardDescription>Wiek backlogu na koniec analizowanego okresu.</CardDescription></CardHeader>
      <CardContent className="grid gap-4 py-2">
        {data.map((item) => (
          <button key={item.bucket} type="button" onClick={() => onSelect(item.label)} className="grid grid-cols-[82px_minmax(0,1fr)_36px] items-center gap-3 text-xs">
            <span className="text-left text-muted-foreground">{item.label}</span>
            <span className="h-2.5 overflow-hidden rounded-full bg-muted"><span className="block h-full rounded-full bg-primary/75 transition-all" style={{ width: `${Math.max(item.value ? 4 : 0, item.value / max * 100)}%` }} /></span>
            <span className="text-right font-medium tabular-nums">{formatNumber(item.value)}</span>
          </button>
        ))}
      </CardContent>
    </Card>
  )
}

function ClientPerformanceTable({ data, onSelect }: { data: ClientPerformance[]; onSelect: (customer: string) => void }) {
  return (
    <Card className="min-w-0">
      <CardHeader><CardTitle>Case’y według klienta</CardTitle><CardDescription>Najaktywniejsi klienci i jakość ich obsługi.</CardDescription></CardHeader>
      <CardContent>
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>Klient</TableHead><TableHead className="text-center">Utworzone</TableHead><TableHead className="text-center">Rozwiązane</TableHead><TableHead className="text-center">Nie dotyczy</TableHead><TableHead className="text-center">Spam</TableHead><TableHead className="text-right">SLA</TableHead><TableHead className="text-right">Śr. odpowiedź</TableHead><TableHead className="text-right">Śr. rozwiązanie</TableHead></TableRow></TableHeader>
            <TableBody>
              {data.slice(0, 6).map((item) => (
                <TableRow key={item.customerId} className="cursor-pointer" onClick={() => onSelect(item.customerName)}>
                  <TableCell className="font-medium">{item.customerName}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.created}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.resolved}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.notApplicable}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.spam}</TableCell>
                  <TableCell className="text-right"><SlaBadge value={item.slaPercentage} /></TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.averageResponseMinutes)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.averageResolutionMinutes)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

function HeatmapCard({ data }: { data: HeatmapPoint[] }) {
  const hours = [0, 3, 6, 9, 12, 15, 18, 21]
  const days = ["Pon", "Wt", "Śr", "Czw", "Pt", "Sob", "Nd"]
  const max = Math.max(...data.map((item) => item.value), 1)
  const getPoint = (dayIndex: number, hour: number) => data.find((item) => item.dayIndex === dayIndex && item.hour === hour)
  return (
    <Card>
      <CardHeader><CardTitle>Heatmapa zgłoszeń</CardTitle><CardDescription>Natężenie nowych spraw według dnia tygodnia i godziny.</CardDescription></CardHeader>
      <CardContent>
        <div className="grid grid-cols-[34px_repeat(8,minmax(26px,1fr))] gap-1.5 text-[10px]">
          <span />
          {hours.map((hour) => <span key={hour} className="text-center text-muted-foreground">{hour}</span>)}
          {days.map((day, dayIndex) => (
            <HeatmapRow key={day} day={day} dayIndex={dayIndex} hours={hours} max={max} getPoint={getPoint} />
          ))}
        </div>
        <div className="mt-4 flex items-center justify-end gap-2 text-[10px] text-muted-foreground">
          <span>Mało</span>{[0.15, 0.35, 0.55, 0.75, 1].map((intensity) => <span key={intensity} className="size-3 rounded-sm" style={{ backgroundColor: `color-mix(in srgb, var(--primary) ${Math.round(intensity * 85)}%, var(--muted))` }} />)}<span>Dużo</span>
        </div>
      </CardContent>
    </Card>
  )
}

function HeatmapRow({ day, dayIndex, hours, max, getPoint }: { day: string; dayIndex: number; hours: number[]; max: number; getPoint: (dayIndex: number, hour: number) => HeatmapPoint | undefined }) {
  return (
    <>
      <span className="flex items-center text-muted-foreground">{day}</span>
      {hours.map((hour) => {
        const point = getPoint(dayIndex, hour)
        const intensity = (point?.value ?? 0) / max
        return <span key={`${day}-${hour}`} title={`${day}, ${hour}:00–${hour + 3}:00 · ${point?.value ?? 0} case’ów`} className="h-7 rounded-[4px] border border-border/40" style={{ backgroundColor: `color-mix(in srgb, var(--primary) ${Math.round(10 + intensity * 80)}%, var(--muted))` }} />
      })}
    </>
  )
}

type SortKey = keyof UserPerformance

function PerformanceTable({ data }: { data: UserPerformance[] }) {
  const [sort, setSort] = useState<{ key: SortKey; direction: "asc" | "desc" }>({ key: "resolved", direction: "desc" })
  const sortedData = useMemo(() => [...data].sort((a, b) => {
    const left = a[sort.key]
    const right = b[sort.key]
    const result = typeof left === "string" && typeof right === "string" ? left.localeCompare(right, "pl") : Number(left) - Number(right)
    return sort.direction === "asc" ? result : -result
  }), [data, sort])

  const setSorting = (key: SortKey) => setSort((current) => ({ key, direction: current.key === key && current.direction === "desc" ? "asc" : "desc" }))

  return (
    <Card className="min-w-0">
      <CardHeader><CardTitle>Wyniki użytkowników</CardTitle><CardDescription>Szczegółowa aktywność, czasy odpowiedzi i jakość SLA w wybranym okresie.</CardDescription></CardHeader>
      <CardContent>
        <div className="overflow-x-auto rounded-lg border">
          <Table className="min-w-[1260px]">
            <TableHeader>
              <TableRow className="bg-muted/40">
                <SortableHead label="Użytkownik" sortKey="userName" sort={sort} onSort={setSorting} />
                <SortableHead label="Przejęte" sortKey="claimed" sort={sort} onSort={setSorting} align="center" />
                <SortableHead label="Rozwiązane" sortKey="resolved" sort={sort} onSort={setSorting} align="center" />
                <SortableHead label="Aktualnie przypisane" sortKey="currentlyAssigned" sort={sort} onSort={setSorting} align="center" definition={metricDefinitions.backlog} />
                <SortableHead label="Nie dotyczy" sortKey="notApplicable" sort={sort} onSort={setSorting} align="center" />
                <SortableHead label="Spam" sortKey="spam" sort={sort} onSort={setSorting} align="center" />
                <SortableHead label="Śr. przejęcie" sortKey="averageClaimMinutes" sort={sort} onSort={setSorting} align="right" definition={metricDefinitions.claim} />
                <SortableHead label="Mediana odpowiedzi" sortKey="medianResponseMinutes" sort={sort} onSort={setSorting} align="right" definition={metricDefinitions.median} />
                <SortableHead label="P90 odpowiedzi" sortKey="p90ResponseMinutes" sort={sort} onSort={setSorting} align="right" definition={metricDefinitions.p90} />
                <SortableHead label="Śr. rozwiązanie" sortKey="averageResolutionMinutes" sort={sort} onSort={setSorting} align="right" definition={metricDefinitions.resolution} />
                <SortableHead label="SLA" sortKey="slaPercentage" sort={sort} onSort={setSorting} align="right" definition={metricDefinitions.sla} />
              </TableRow>
            </TableHeader>
            <TableBody>
              {sortedData.map((item) => (
                <TableRow key={item.userId}>
                  <TableCell className="font-medium">{item.userName}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.claimed}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.resolved}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.currentlyAssigned}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.notApplicable}</TableCell>
                  <TableCell className="text-center tabular-nums">{item.spam}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.averageClaimMinutes)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.medianResponseMinutes)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.p90ResponseMinutes)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(item.averageResolutionMinutes)}</TableCell>
                  <TableCell className="text-right"><SlaBadge value={item.slaPercentage} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

function SortableHead({ label, sortKey, sort, onSort, align = "left", definition }: { label: string; sortKey: SortKey; sort: { key: SortKey; direction: "asc" | "desc" }; onSort: (key: SortKey) => void; align?: "left" | "center" | "right"; definition?: string }) {
  const Icon = sort.key !== sortKey ? ArrowUpDown : sort.direction === "asc" ? ArrowUp : ArrowDown
  return (
    <TableHead className={cn(align === "center" && "text-center", align === "right" && "text-right")}>
      <button type="button" onClick={() => onSort(sortKey)} className={cn("inline-flex items-center gap-1 whitespace-nowrap hover:text-foreground", align === "center" && "justify-center", align === "right" && "justify-end")}>
        {label}{definition && <Tooltip><TooltipTrigger render={<span role="button" tabIndex={0} aria-label={`Definicja: ${label}`} />}><Info className="size-3.5" /></TooltipTrigger><TooltipContent className="max-w-72">{definition}</TooltipContent></Tooltip>}<Icon className="size-3.5 opacity-70" />
      </button>
    </TableHead>
  )
}

function SlaBadge({ value }: { value: number }) {
  return <Badge variant="secondary" className={cn("tabular-nums", value >= 0.85 ? "bg-success/10 text-success" : value >= 0.7 ? "bg-warning/15 text-warning-foreground" : "bg-destructive/10 text-destructive")}>{value >= 0.85 ? <CheckCircle2 /> : <Gauge />}{formatPercent(value)}</Badge>
}

function DashboardFilterSelect({ value, onChange, options, label, allLabel }: { value: string; onChange: (value: string) => void; options: { value: string; label: string }[]; label: string; allLabel: string }) {
  const selectedLabel = value === ALL_VALUE ? allLabel : options.find((option) => option.value === value)?.label ?? allLabel
  return (
    <Select value={value} onValueChange={(next) => onChange(String(next))}>
      <SelectTrigger className="h-8 w-full sm:w-44" aria-label={label}>
        <SelectValue>{selectedLabel}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL_VALUE}>{allLabel}</SelectItem>
        {options.map((option) => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}
      </SelectContent>
    </Select>
  )
}

const axisTick = { fill: "var(--muted-foreground)", fontSize: 11 }

function getPresetRange(preset: Exclude<DatePreset, "custom">) {
  const end = new Date(`${analyticsToday}T12:00:00.000Z`)
  if (preset === "yesterday") {
    end.setUTCDate(end.getUTCDate() - 1)
    const date = end.toISOString().slice(0, 10)
    return { from: date, to: date }
  }
  if (preset === "this_month") return { from: `${analyticsToday.slice(0, 8)}01`, to: analyticsToday }
  if (preset === "previous_month") {
    const firstCurrent = new Date(`${analyticsToday.slice(0, 8)}01T12:00:00.000Z`)
    const lastPrevious = new Date(firstCurrent)
    lastPrevious.setUTCDate(0)
    const firstPrevious = new Date(Date.UTC(lastPrevious.getUTCFullYear(), lastPrevious.getUTCMonth(), 1, 12))
    return { from: firstPrevious.toISOString().slice(0, 10), to: lastPrevious.toISOString().slice(0, 10) }
  }
  const days = preset === "today" ? 1 : preset === "7days" ? 7 : preset === "30days" ? 30 : 90
  const start = new Date(end)
  start.setUTCDate(start.getUTCDate() - (days - 1))
  return { from: start.toISOString().slice(0, 10), to: analyticsToday }
}

function getComparisonLabel(preset: DatePreset, range?: string) {
  if (preset === "7days") return "vs poprzednie 7 dni"
  if (preset === "30days") return "vs poprzednie 30 dni"
  if (preset === "90days") return "vs poprzednie 90 dni"
  return range ? `vs ${range}` : "vs poprzedni okres"
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
