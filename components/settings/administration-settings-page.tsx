"use client"

import { useEffect, useMemo, useState, type ReactNode } from "react"
import {
  BellRing,
  CalendarClock,
  CheckCircle2,
  Clock3,
  Info,
  MessageSquareText,
  MoonStar,
  Pencil,
  PlugZap,
  Plus,
  Radio,
  RefreshCw,
  Save,
  Settings2,
  ShieldCheck,
  Timer,
  Trash2,
  TriangleAlert,
  Unplug,
} from "lucide-react"
import { PageHeader } from "@/components/layout/page-header"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { ConfirmDialog } from "@/components/design-system/confirm-dialog"
import { ErrorState } from "@/components/design-system/data-states"
import { SettingsPageSkeleton } from "@/components/design-system/page-skeletons"
import { notify } from "@/components/design-system/notify"
import { PlatformIcon } from "@/components/design-system/platform-badge"
import {
  allAdministrationPermissions,
  administrationPermissionLabels,
} from "@/lib/domain/administration"
import type {
  AdministrationPermission,
  AdministrationSettings,
  GeneralSettings,
  ManagedChannel,
  ManagedIntegration,
  NotificationDestination,
  NotificationType,
  OutOfOfficeSettings,
  RolePermissions,
  ScheduleException,
  SlaSettings,
  WorkScheduleSettings,
} from "@/lib/domain/administration"
import { channelLabels } from "@/lib/domain/labels"
import { formatDate, formatDateTime } from "@/lib/format"
import {
  useAdministrationSettings,
  useAdministrationSettingsActions,
} from "@/lib/services/queries"
import type { AdministrationSectionInput } from "@/lib/services/queries"

const tabItems = [
  { value: "general", label: "Ogólne", icon: Settings2 },
  { value: "sla", label: "SLA", icon: Timer },
  { value: "schedule", label: "Godziny pracy", icon: CalendarClock },
  { value: "out-of-office", label: "Poza biurem", icon: MoonStar },
  { value: "integrations", label: "Integracje", icon: PlugZap },
  { value: "channels", label: "Kanały", icon: Radio },
  { value: "notifications", label: "Powiadomienia", icon: BellRing },
  { value: "permissions", label: "Uprawnienia", icon: ShieldCheck },
] as const

export function AdministrationSettingsPage() {
  const settingsQuery = useAdministrationSettings()
  const actions = useAdministrationSettingsActions()
  const unavailableIntegrations = settingsQuery.data?.integrations.filter((item) => item.status !== "connected") ?? []

  return (
    <>
      <PageHeader
        title="Ustawienia"
        description="Zasady pracy skrzynki, integracje i uprawnienia zespołu"
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={() => void settingsQuery.refetch()}
            disabled={settingsQuery.isFetching}
          >
            <RefreshCw className={settingsQuery.isFetching ? "animate-spin" : undefined} />
            <span className="hidden sm:inline">Odśwież</span>
          </Button>
        }
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1500px] flex-col gap-4 p-4 md:p-6">
          <Alert className="bg-info/5 text-info">
            <Info />
            <AlertTitle>Konfiguracja demonstracyjna</AlertTitle>
            <AlertDescription>
              Wszystkie zmiany są obsługiwane przez lokalne, typowane serwisy mock i nie łączą się z zewnętrznymi dostawcami.
            </AlertDescription>
          </Alert>

          {unavailableIntegrations.length > 0 && (
            <Alert className="border-warning/30 bg-warning/10 text-warning-foreground">
              <Unplug />
              <AlertTitle>Nie wszystkie integracje są połączone</AlertTitle>
              <AlertDescription>
                {unavailableIntegrations.map((item) => channelLabels[item.platform]).join(", ")} — sprawdź status w zakładce Integracje. Odbiór nowych wiadomości z tych źródeł może być ograniczony.
              </AlertDescription>
            </Alert>
          )}

          {settingsQuery.isLoading ? (
            <SettingsPageSkeleton />
          ) : settingsQuery.isError || !settingsQuery.data ? (
            <div className="rounded-lg border bg-card"><ErrorState onRetry={() => void settingsQuery.refetch()} /></div>
          ) : (
            <Tabs defaultValue="general" className="min-w-0 gap-4">
              <div className="overflow-x-auto rounded-lg border bg-card px-2">
                <TabsList variant="line" className="h-11 min-w-max">
                  {tabItems.map(({ value, label, icon: Icon }) => (
                    <TabsTrigger key={value} value={value} className="px-3">
                      <Icon /> {label}
                    </TabsTrigger>
                  ))}
                </TabsList>
              </div>

              <TabsContent value="general"><GeneralPanel data={settingsQuery.data.general} actions={actions} /></TabsContent>
              <TabsContent value="sla"><SlaPanel data={settingsQuery.data.sla} actions={actions} /></TabsContent>
              <TabsContent value="schedule"><SchedulePanel data={settingsQuery.data.schedule} actions={actions} /></TabsContent>
              <TabsContent value="out-of-office"><OutOfOfficePanel data={settingsQuery.data.outOfOffice} actions={actions} /></TabsContent>
              <TabsContent value="integrations"><IntegrationsPanel data={settingsQuery.data.integrations} actions={actions} /></TabsContent>
              <TabsContent value="channels"><ChannelsPanel data={settingsQuery.data.channels} actions={actions} /></TabsContent>
              <TabsContent value="notifications"><NotificationsPanel data={settingsQuery.data.notifications} integrations={settingsQuery.data.integrations} actions={actions} /></TabsContent>
              <TabsContent value="permissions"><PermissionsPanel data={settingsQuery.data.rolePermissions} actions={actions} /></TabsContent>
            </Tabs>
          )}
        </div>
      </main>
    </>
  )
}

type SettingsActions = ReturnType<typeof useAdministrationSettingsActions>

function GeneralPanel({ data, actions }: { data: GeneralSettings; actions: SettingsActions }) {
  const [form, setForm] = useState(data)

  return (
    <SettingsCard title="Ogólne" description="Podstawowe zachowanie i wygląd skrzynki.">
      <div className="grid max-w-2xl gap-4 sm:grid-cols-2">
        <Field label="Nazwa organizacji">
          <Input value={form.organizationName} onChange={(event) => setForm({ ...form, organizationName: event.target.value })} />
        </Field>
        <Field label="Język interfejsu">
          <Select value="pl" disabled><SelectTrigger className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="pl">Polski</SelectItem></SelectContent></Select>
        </Field>
        <SettingSwitch label="Widok kompaktowy" description="Zmniejsza odstępy w tabelach i listach." checked={form.compactMode} onChange={(checked) => setForm({ ...form, compactMode: checked })} />
      </div>
      <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "general", form, "Zapisano ustawienia ogólne")} />
    </SettingsCard>
  )
}

function SlaPanel({ data, actions }: { data: SlaSettings; actions: SettingsActions }) {
  const [form, setForm] = useState(data)
  const example = useMemo(() => calculateSlaExample(form), [form])

  const numberFields: { key: keyof Pick<SlaSettings, "firstResponseMinutes" | "unclaimedReminderMinutes" | "inProgressReminderMinutes" | "warningBeforeDeadlineMinutes" | "repeatedBreachMinutes">; label: string; hint: string }[] = [
    { key: "firstResponseMinutes", label: "SLA pierwszej odpowiedzi", hint: "Czas od otrzymania do pierwszej odpowiedzi." },
    { key: "unclaimedReminderMinutes", label: "Przypomnienie o nieprzejętym case’ie", hint: "Pierwszy alert, gdy case nadal nie ma właściciela." },
    { key: "inProgressReminderMinutes", label: "Przypomnienie w trakcie", hint: "Alert o zbyt długiej pracy bez aktualizacji." },
    { key: "warningBeforeDeadlineMinutes", label: "Ostrzeżenie przed terminem", hint: "Kiedy case otrzymuje stan ostrzegawczy." },
    { key: "repeatedBreachMinutes", label: "Interwał ponawiania przekroczenia", hint: "Jak często ponawiać alert po przekroczeniu." },
  ]

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <SettingsCard title="Polityka SLA" description="Czasy podawane są w minutach i obowiązują wszystkie kanały z tą polityką.">
        <div className="grid gap-4 md:grid-cols-2">
          {numberFields.map((field) => (
            <Field key={field.key} label={field.label} hint={field.hint}>
              <div className="relative"><Input type="number" min={1} value={form[field.key]} onChange={(event) => setForm({ ...form, [field.key]: Math.max(1, Number(event.target.value)) })} className="pr-14" /><span className="absolute right-2.5 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">min</span></div>
            </Field>
          ))}
        </div>
        <div className="grid gap-2 border-t pt-4 sm:grid-cols-2">
          <SettingSwitch label="Tylko w godzinach pracy" description="Odliczanie pomija zamknięte godziny i wyjątki." checked={form.businessHoursOnly} onChange={(checked) => setForm({ ...form, businessHoursOnly: checked })} />
          <SettingSwitch label="Wstrzymaj przy oczekiwaniu" description="SLA nie biegnie podczas oczekiwania na klienta." checked={form.pauseWhileWaiting} onChange={(checked) => setForm({ ...form, pauseWhileWaiting: checked })} />
        </div>
        <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "sla", form, "Zapisano politykę SLA")} />
      </SettingsCard>

      <Card className="h-fit border-primary/20 bg-primary/[0.03]">
        <CardHeader><CardTitle className="flex items-center gap-2"><Clock3 className="size-4 text-primary" /> Przykład na żywo</CardTitle><CardDescription>Case otrzymany w poniedziałek o 16:30.</CardDescription></CardHeader>
        <CardContent className="grid gap-4">
          <TimelineItem label="Otrzymano" value="pon., 16:30" />
          <TimelineItem label="Ostrzeżenie" value={example.warning} accent="warning" />
          <TimelineItem label="Termin SLA" value={example.deadline} accent="destructive" />
          <p className="rounded-lg bg-muted/60 p-3 text-xs text-muted-foreground">
            {form.businessHoursOnly ? "Po 17:00 licznik zatrzymuje się i rusza ponownie o 08:00 następnego dnia roboczego." : "SLA liczone jest w sposób ciągły, również poza godzinami pracy."}
          </p>
        </CardContent>
      </Card>
    </div>
  )
}

function SchedulePanel({ data, actions }: { data: WorkScheduleSettings; actions: SettingsActions }) {
  const [form, setForm] = useState(data)
  const [exceptionOpen, setExceptionOpen] = useState(false)
  const [exception, setException] = useState<Omit<ScheduleException, "id">>({ date: "", name: "", closed: true })

  const updateDay = (index: number, patch: Partial<WorkScheduleSettings["days"][number]>) => {
    setForm((current) => ({ ...current, days: current.days.map((day, dayIndex) => dayIndex === index ? { ...day, ...patch } : day) }))
  }

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
      <SettingsCard title="Tygodniowy harmonogram" description="Godziny używane przez SLA, automatyczne odpowiedzi i przypomnienia.">
        <Field label="Strefa czasowa">
          <Select value={form.timezone} onValueChange={(value) => setForm({ ...form, timezone: String(value) })}>
            <SelectTrigger className="w-full sm:w-72"><SelectValue /></SelectTrigger>
            <SelectContent><SelectItem value="Europe/Warsaw">Europe/Warsaw (CET/CEST)</SelectItem><SelectItem value="Europe/London">Europe/London</SelectItem><SelectItem value="UTC">UTC</SelectItem></SelectContent>
          </Select>
        </Field>
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>Dzień</TableHead><TableHead>Otwarte</TableHead><TableHead>Godziny</TableHead><TableHead>Przerwa</TableHead><TableHead>Godziny przerwy</TableHead></TableRow></TableHeader>
            <TableBody>
              {form.days.map((day, index) => (
                <TableRow key={day.key}>
                  <TableCell className="font-medium">{day.label}</TableCell>
                  <TableCell><Switch checked={day.enabled} onCheckedChange={(checked) => updateDay(index, { enabled: checked })} aria-label={`${day.label}: otwarte`} /></TableCell>
                  <TableCell><div className="flex items-center gap-1.5"><Input type="time" value={day.start} disabled={!day.enabled} onChange={(event) => updateDay(index, { start: event.target.value })} className="w-28" /><span className="text-muted-foreground">–</span><Input type="time" value={day.end} disabled={!day.enabled} onChange={(event) => updateDay(index, { end: event.target.value })} className="w-28" /></div></TableCell>
                  <TableCell><Switch checked={day.breakEnabled} disabled={!day.enabled} onCheckedChange={(checked) => updateDay(index, { breakEnabled: checked })} aria-label={`${day.label}: przerwa`} /></TableCell>
                  <TableCell><div className="flex items-center gap-1.5"><Input type="time" value={day.breakStart} disabled={!day.enabled || !day.breakEnabled} onChange={(event) => updateDay(index, { breakStart: event.target.value })} className="w-28" /><span className="text-muted-foreground">–</span><Input type="time" value={day.breakEnd} disabled={!day.enabled || !day.breakEnabled} onChange={(event) => updateDay(index, { breakEnd: event.target.value })} className="w-28" /></div></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
        <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "schedule", form, "Zapisano harmonogram pracy")} />
      </SettingsCard>

      <SettingsCard title="Święta i wyjątki" description="Daty, w których standardowy harmonogram nie obowiązuje." actions={<Button variant="outline" size="sm" onClick={() => setExceptionOpen(true)}><Plus /> Dodaj</Button>}>
        <div className="grid gap-2">
          {form.exceptions.map((item) => (
            <div key={item.id} className="flex items-center gap-3 rounded-lg border p-3">
              <div className="flex size-9 items-center justify-center rounded-lg bg-muted"><CalendarClock className="size-4" /></div>
              <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium">{item.name}</p><p className="text-xs text-muted-foreground">{formatDate(item.date)} · {item.closed ? "zamknięte" : `${item.start}–${item.end}`}</p></div>
              <Button variant="ghost" size="icon-sm" aria-label={`Usuń ${item.name}`} onClick={() => setForm({ ...form, exceptions: form.exceptions.filter((entry) => entry.id !== item.id) })}><Trash2 /></Button>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">Usunięcia zostaną zastosowane po zapisaniu harmonogramu.</p>
      </SettingsCard>

      <Dialog open={exceptionOpen} onOpenChange={setExceptionOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Dodaj wyjątek</DialogTitle><DialogDescription>Ustaw dzień zamknięty lub niestandardowe godziny pracy.</DialogDescription></DialogHeader>
          <div className="grid gap-3">
            <Field label="Nazwa"><Input value={exception.name} onChange={(event) => setException({ ...exception, name: event.target.value })} /></Field>
            <Field label="Data"><Input type="date" value={exception.date} onChange={(event) => setException({ ...exception, date: event.target.value })} /></Field>
            <SettingSwitch label="Dzień zamknięty" description="W tym dniu SLA nie będzie naliczane." checked={exception.closed} onChange={(checked) => setException({ ...exception, closed: checked })} />
            {!exception.closed && <div className="grid grid-cols-2 gap-3"><Field label="Od"><Input type="time" value={exception.start ?? "08:00"} onChange={(event) => setException({ ...exception, start: event.target.value })} /></Field><Field label="Do"><Input type="time" value={exception.end ?? "16:00"} onChange={(event) => setException({ ...exception, end: event.target.value })} /></Field></div>}
          </div>
          <DialogFooter><DialogClose render={<Button variant="outline" />}>Anuluj</DialogClose><Button onClick={() => { if (!exception.date || !exception.name.trim()) { notify.warning("Uzupełnij nazwę i datę"); return }; setForm({ ...form, exceptions: [...form.exceptions, { ...exception, id: `ex-${Date.now()}` }] }); setExceptionOpen(false); setException({ date: "", name: "", closed: true }); notify.success("Dodano wyjątek", "Zapisz harmonogram, aby utrwalić zmianę.") }}>Dodaj wyjątek</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

const placeholders = ["customer_name", "next_opening_date", "next_opening_time", "timezone"] as const

function OutOfOfficePanel({ data, actions }: { data: OutOfOfficeSettings; actions: SettingsActions }) {
  const [form, setForm] = useState(data)
  const preview = form.template
    .replaceAll("{{customer_name}}", "Joanno")
    .replaceAll("{{next_opening_date}}", "4 sierpnia 2026")
    .replaceAll("{{next_opening_time}}", "08:00")
    .replaceAll("{{timezone}}", "Europe/Warsaw")

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_420px]">
      <SettingsCard title="Poza biurem" description="Automatyczna odpowiedź wysyłana poza harmonogramem pracy.">
        <SettingSwitch label="Włącz automatyczną odpowiedź" description="Dotyczy wszystkich włączonych kanałów." checked={form.enabled} onChange={(checked) => setForm({ ...form, enabled: checked })} />
        <Field label="Szablon wiadomości" hint="Kliknij zmienną, aby dodać ją na końcu treści.">
          <textarea value={form.template} onChange={(event) => setForm({ ...form, template: event.target.value })} rows={7} className="w-full resize-y rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30" />
        </Field>
        <div className="flex flex-wrap gap-2">
          {placeholders.map((placeholder) => <Button key={placeholder} variant="secondary" size="xs" onClick={() => setForm({ ...form, template: `${form.template}${form.template.endsWith(" ") ? "" : " "}{{${placeholder}}}` })}>{`{{${placeholder}}}`}</Button>)}
        </div>
        <SettingSwitch label="Wyślij raz na okres zamknięcia" description="Klient nie otrzyma wielu identycznych wiadomości przed kolejnym otwarciem." checked={form.sendOncePerClosure} onChange={(checked) => setForm({ ...form, sendOncePerClosure: checked })} />
        <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "outOfOffice", form, "Zapisano ustawienia nieobecności")} />
      </SettingsCard>
      <Card className="h-fit"><CardHeader><CardTitle className="flex items-center gap-2"><MessageSquareText className="size-4 text-primary" /> Podgląd</CardTitle><CardDescription>Przykładowa wiadomość dla klienta.</CardDescription></CardHeader><CardContent><div className="rounded-2xl rounded-bl-sm bg-muted p-4 text-sm leading-relaxed">{preview}</div><p className="mt-3 text-xs text-muted-foreground">Zmienne zostaną uzupełnione na podstawie klienta i aktywnego harmonogramu.</p></CardContent></Card>
    </div>
  )
}

const integrationStatusLabels: Record<ManagedIntegration["status"], string> = {
  connected: "Połączona",
  disconnected: "Rozłączona",
  reauthorization: "Wymaga ponownej autoryzacji",
}

const integrationHealthLabels: Record<ManagedIntegration["health"], string> = {
  healthy: "Połączenie sprawne",
  degraded: "Wymaga uwagi",
  unavailable: "Brak połączenia",
}

function IntegrationsPanel({ data, actions }: { data: ManagedIntegration[]; actions: SettingsActions }) {
  const [configured, setConfigured] = useState<ManagedIntegration | null>(null)
  const [workspace, setWorkspace] = useState("")
  const [disconnected, setDisconnected] = useState<ManagedIntegration | null>(null)

  useEffect(() => {
    setWorkspace(configured?.workspace ?? "")
  }, [configured])

  const test = async (integration: ManagedIntegration) => {
    try {
      await actions.testIntegration.mutateAsync(integration.id)
      notify.success("Test połączenia zakończony powodzeniem", channelLabels[integration.platform])
    } catch (error) {
      notify.error("Test połączenia nie powiódł się", getErrorMessage(error))
    }
  }

  const reauthorize = async (integration: ManagedIntegration) => {
    try {
      await actions.setIntegrationStatus.mutateAsync({ id: integration.id, status: "connected" })
      notify.success("Autoryzacja została odnowiona", channelLabels[integration.platform])
    } catch (error) {
      notify.error("Nie udało się odnowić autoryzacji", getErrorMessage(error))
    }
  }

  return (
    <div className="grid gap-4">
      <Alert className="border-warning/30 bg-warning/10 text-warning-foreground">
        <TriangleAlert />
        <AlertTitle>Zakres Microsoft Teams</AlertTitle>
        <AlertDescription>
          Początkowo obsługiwane są standardowe kanały i czaty grupowe z RSC. Kanały prywatne i udostępnione wymagają osobnej walidacji.
        </AlertDescription>
      </Alert>

      <div className="grid gap-4 lg:grid-cols-3">
        {data.map((integration) => (
          <Card key={integration.id} className="min-w-0">
            <CardHeader>
              <div className="flex items-start gap-3">
                <PlatformIcon channel={integration.platform} className="size-10" />
                <div className="min-w-0 flex-1">
                  <CardTitle>{channelLabels[integration.platform]}</CardTitle>
                  <CardDescription className="truncate">{integration.workspace || "Nie skonfigurowano"}</CardDescription>
                </div>
                <Badge
                  variant="secondary"
                  className={
                    integration.status === "connected"
                      ? "bg-success/10 text-success"
                      : integration.status === "reauthorization"
                        ? "bg-warning/15 text-warning-foreground"
                        : "text-muted-foreground"
                  }
                >
                  {integration.status === "connected" ? <CheckCircle2 /> : integration.status === "reauthorization" ? <TriangleAlert /> : <Unplug />}
                  {integrationStatusLabels[integration.status]}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="grid gap-4">
              <dl className="grid gap-3 text-xs">
                <div className="flex items-start justify-between gap-3"><dt className="text-muted-foreground">Obszar roboczy / dzierżawa</dt><dd className="max-w-48 truncate text-right font-mono">{integration.workspace || "—"}</dd></div>
                <div className="flex items-start justify-between gap-3"><dt className="text-muted-foreground">Ostatnie zdarzenie</dt><dd className="text-right">{integration.lastEventAt ? formatDateTime(integration.lastEventAt) : "Brak"}</dd></div>
                <div className="flex items-center justify-between gap-3"><dt className="text-muted-foreground">Health check</dt><dd className="flex items-center gap-1.5">{integration.health === "healthy" ? <CheckCircle2 className="size-3.5 text-success" /> : integration.health === "degraded" ? <TriangleAlert className="size-3.5 text-warning-foreground" /> : <Unplug className="size-3.5 text-destructive" />}{integrationHealthLabels[integration.health]}</dd></div>
              </dl>
              <div className="flex flex-wrap gap-2 border-t pt-3">
                <Button variant="outline" size="sm" onClick={() => setConfigured(integration)}><Settings2 /> Konfiguruj</Button>
                <Button variant="outline" size="sm" disabled={integration.status !== "connected" || actions.testIntegration.isPending} onClick={() => void test(integration)}><PlugZap /> Testuj</Button>
                {integration.status === "reauthorization" && <Button size="sm" onClick={() => void reauthorize(integration)}><RefreshCw /> Autoryzuj ponownie</Button>}
                {integration.status !== "disconnected" && <Button variant="destructive" size="sm" onClick={() => setDisconnected(integration)}><Unplug /> Rozłącz</Button>}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Dialog open={Boolean(configured)} onOpenChange={(open) => !open && setConfigured(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Konfiguruj {configured ? channelLabels[configured.platform] : "integrację"}</DialogTitle><DialogDescription>W środowisku demonstracyjnym zapisujemy wyłącznie identyfikator obszaru roboczego, dzierżawy lub bota.</DialogDescription></DialogHeader>
          <Field label="Obszar roboczy / dzierżawa / bot"><Input value={workspace} onChange={(event) => setWorkspace(event.target.value)} placeholder="np. firma.slack.com" /></Field>
          <Alert><Info /><AlertDescription>Prawdziwy proces OAuth zostanie obsłużony przez docelowy backend.</AlertDescription></Alert>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>Anuluj</DialogClose>
            <Button disabled={!workspace.trim() || actions.configureIntegration.isPending} onClick={async () => {
              if (!configured) return
              try {
                await actions.configureIntegration.mutateAsync({ id: configured.id, workspace })
                notify.success("Zapisano konfigurację", channelLabels[configured.platform])
                setConfigured(null)
              } catch (error) { notify.error("Nie udało się zapisać konfiguracji", getErrorMessage(error)) }
            }}>{actions.configureIntegration.isPending ? "Zapisywanie…" : "Zapisz konfigurację"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={Boolean(disconnected)}
        onOpenChange={(open) => !open && setDisconnected(null)}
        title="Rozłączyć integrację?"
        description={disconnected ? `Nowe zdarzenia z ${channelLabels[disconnected.platform]} przestaną trafiać do skrzynki. Istniejące case’y pozostaną dostępne.` : undefined}
        confirmLabel="Rozłącz"
        destructive
        onConfirm={() => {
          if (!disconnected) return
          const integration = disconnected
          void actions.setIntegrationStatus.mutateAsync({ id: integration.id, status: "disconnected" }).then(() => {
            notify.success("Integracja została rozłączona", channelLabels[integration.platform])
            setDisconnected(null)
          }).catch((error) => notify.error("Nie udało się rozłączyć integracji", getErrorMessage(error)))
        }}
      />
    </div>
  )
}

function ChannelsPanel({ data, actions }: { data: ManagedChannel[]; actions: SettingsActions }) {
  const setIgnored = async (channel: ManagedChannel, ignored: boolean) => {
    try {
      await actions.setChannelIgnored.mutateAsync({ id: channel.id, ignored })
      notify.success(ignored ? "Kanał będzie ignorowany" : "Kanał będzie kwalifikowany", channel.channelName)
    } catch (error) {
      notify.error("Nie udało się zmienić reguły kanału", getErrorMessage(error))
    }
  }

  return (
    <SettingsCard title="Ignorowane kanały" description="Wybierz kanały, których wiadomości nie mają być kwalifikowane jako sprawy do zrobienia.">
      <Alert>
        <Info />
        <AlertTitle>Wiadomości bez case’a i SLA</AlertTitle>
        <AlertDescription>Wiadomość z ignorowanego kanału pozostaje zwykłą wiadomością. Nie tworzy sprawy do zrobienia i nie uruchamia dla niej SLA.</AlertDescription>
      </Alert>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>Platforma</TableHead><TableHead>Kanał</TableHead><TableHead>Klient</TableHead><TableHead>Ignoruj</TableHead><TableHead>Ostatnia wiadomość</TableHead></TableRow></TableHeader>
          <TableBody>
            {data.map((channel) => (
              <TableRow key={channel.id}>
                <TableCell><div className="flex items-center gap-2"><PlatformIcon channel={channel.platform} /> <span className="text-xs">{channelLabels[channel.platform]}</span></div></TableCell>
                <TableCell className="font-medium">{channel.channelName}</TableCell>
                <TableCell>{channel.customer}</TableCell>
                <TableCell><div className="flex items-center gap-2"><Switch checked={channel.ignored} onCheckedChange={(checked) => void setIgnored(channel, checked)} aria-label={`${channel.channelName}: ${channel.ignored ? "ignorowany" : "kwalifikowany"}`} /><span className={`text-xs ${channel.ignored ? "text-warning-foreground" : "text-muted-foreground"}`}>{channel.ignored ? "Ignorowany" : "Kwalifikowany"}</span></div></TableCell>
                <TableCell className="text-xs text-muted-foreground">{channel.lastMessageAt ? formatDateTime(channel.lastMessageAt) : "Brak"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <p className="text-xs text-muted-foreground">Zmiana dotyczy nowych wiadomości przychodzących po zapisaniu reguły.</p>
    </SettingsCard>
  )
}

const notificationTypeLabels: Record<NotificationType, string> = {
  unclaimed_too_long: "Nieprzejęty zbyt długo",
  in_progress_too_long: "W trakcie zbyt długo",
  sla_warning: "Ostrzeżenie SLA",
  sla_breached: "Przekroczone SLA",
  integration_disconnected: "Rozłączona integracja",
}

const blankNotification: NotificationDestination = {
  id: "",
  name: "",
  provider: "slack",
  integrationId: "int-slack",
  channelName: "",
  types: ["sla_warning", "sla_breached"],
  enabled: true,
}

function NotificationsPanel({ data, integrations, actions }: { data: NotificationDestination[]; integrations: ManagedIntegration[]; actions: SettingsActions }) {
  const [edited, setEdited] = useState<NotificationDestination | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [removed, setRemoved] = useState<NotificationDestination | null>(null)

  const openCreate = () => { setEdited({ ...blankNotification }); setDialogOpen(true) }
  const openEdit = (item: NotificationDestination) => { setEdited(structuredClone(item)); setDialogOpen(true) }

  const saveDestinations = async (next: NotificationDestination[], message: string) => {
    try {
      await actions.saveSection.mutateAsync({ key: "notifications", value: next })
      notify.success(message)
      setDialogOpen(false)
      setRemoved(null)
    } catch (error) { notify.error("Nie udało się zapisać powiadomień", getErrorMessage(error)) }
  }

  const toggle = async (item: NotificationDestination, enabled: boolean) => {
    try {
      await actions.toggleNotification.mutateAsync({ id: item.id, enabled })
      notify.success(enabled ? "Powiadomienia włączone" : "Powiadomienia wyłączone", item.name)
    } catch (error) { notify.error("Nie udało się zmienić powiadomień", getErrorMessage(error)) }
  }

  return (
    <SettingsCard title="Cele powiadomień" description="Alerty operacyjne wysyłane do wybranych kanałów." actions={<Button size="sm" onClick={openCreate}><Plus /> Dodaj cel</Button>}>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>Nazwa</TableHead><TableHead>Dostawca</TableHead><TableHead>Integracja</TableHead><TableHead>Kanał</TableHead><TableHead className="min-w-80">Typy</TableHead><TableHead>Włączone</TableHead><TableHead><span className="sr-only">Akcje</span></TableHead></TableRow></TableHeader>
          <TableBody>
            {data.map((item) => (
              <TableRow key={item.id}>
                <TableCell className="font-medium">{item.name}</TableCell>
                <TableCell><div className="flex items-center gap-2"><PlatformIcon channel={item.provider} /> {channelLabels[item.provider]}</div></TableCell>
                <TableCell className="font-mono text-xs">{integrations.find((integration) => integration.id === item.integrationId)?.workspace ?? "—"}</TableCell>
                <TableCell>{item.channelName}</TableCell>
                <TableCell><div className="flex max-w-96 flex-wrap gap-1">{item.types.map((type) => <Badge key={type} variant="secondary">{notificationTypeLabels[type]}</Badge>)}</div></TableCell>
                <TableCell><div className="flex items-center gap-2"><Switch checked={item.enabled} onCheckedChange={(checked) => void toggle(item, checked)} aria-label={`${item.name}: ${item.enabled ? "włączone" : "wyłączone"}`} /><EnabledState enabled={item.enabled} /></div></TableCell>
                <TableCell>
                  <Button variant="ghost" size="icon-sm" aria-label={`Edytuj ${item.name}`} onClick={() => openEdit(item)}><Pencil /></Button>
                  <Button variant="ghost" size="icon-sm" aria-label={`Usuń ${item.name}`} onClick={() => setRemoved(item)}><Trash2 /></Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-lg">
          <DialogHeader><DialogTitle>{edited?.id ? "Edytuj cel powiadomień" : "Dodaj cel powiadomień"}</DialogTitle><DialogDescription>Wybierz integrację, kanał docelowy i zdarzenia generujące alert.</DialogDescription></DialogHeader>
          {edited && <div className="grid gap-4">
            <Field label="Nazwa"><Input value={edited.name} onChange={(event) => setEdited({ ...edited, name: event.target.value })} /></Field>
            <Field label="Integracja">
              <Select value={edited.integrationId} onValueChange={(value) => { const integration = integrations.find((item) => item.id === String(value)); if (integration) setEdited({ ...edited, integrationId: integration.id, provider: integration.platform }) }}>
                <SelectTrigger className="w-full"><SelectValue /></SelectTrigger><SelectContent>{integrations.map((integration) => <SelectItem key={integration.id} value={integration.id}>{channelLabels[integration.platform]} · {integration.workspace}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
            <Field label="Kanał docelowy"><Input value={edited.channelName} onChange={(event) => setEdited({ ...edited, channelName: event.target.value })} placeholder="#support-alerts" /></Field>
            <fieldset className="grid gap-2 rounded-lg border p-3"><legend className="px-1 text-sm font-medium">Typy powiadomień</legend>{(Object.entries(notificationTypeLabels) as [NotificationType, string][]).map(([type, label]) => <label key={type} className="flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-primary" checked={edited.types.includes(type)} onChange={(event) => setEdited({ ...edited, types: event.target.checked ? [...edited.types, type] : edited.types.filter((item) => item !== type) })} />{label}</label>)}</fieldset>
            <SettingSwitch label="Cel aktywny" description="Wyłączony cel nie otrzymuje żadnych alertów." checked={edited.enabled} onChange={(enabled) => setEdited({ ...edited, enabled })} />
          </div>}
          <DialogFooter><DialogClose render={<Button variant="outline" />}>Anuluj</DialogClose><Button disabled={!edited?.name.trim() || !edited.channelName.trim() || edited.types.length === 0 || actions.saveSection.isPending} onClick={() => { if (!edited) return; const saved = { ...edited, id: edited.id || `not-${Date.now()}` }; const next = edited.id ? data.map((item) => item.id === edited.id ? saved : item) : [...data, saved]; void saveDestinations(next, edited.id ? "Zapisano cel powiadomień" : "Dodano cel powiadomień") }}>Zapisz</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog open={Boolean(removed)} onOpenChange={(open) => !open && setRemoved(null)} title="Usunąć cel powiadomień?" description={removed ? `Alerty nie będą już wysyłane do „${removed.name}”.` : undefined} confirmLabel="Usuń" destructive onConfirm={() => { if (removed) void saveDestinations(data.filter((item) => item.id !== removed.id), "Usunięto cel powiadomień") }} />
    </SettingsCard>
  )
}

function PermissionsPanel({ data, actions }: { data: RolePermissions[]; actions: SettingsActions }) {
  const [form, setForm] = useState(data)

  const toggle = (role: RolePermissions["role"], permission: AdministrationPermission, checked: boolean) => {
    setForm((current) => current.map((item) => item.role !== role ? item : {
      ...item,
      permissions: checked ? [...new Set([...item.permissions, permission])] : item.permissions.filter((entry) => entry !== permission),
    }))
  }

  return (
    <SettingsCard title="Uprawnienia ról" description="Domyślny zakres dostępu dla użytkowników i administratorów. Indywidualne wyjątki ustawisz na ekranie użytkowników.">
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>Uprawnienie</TableHead><TableHead className="w-32 text-center">Użytkownik</TableHead><TableHead className="w-32 text-center">Administrator</TableHead></TableRow></TableHeader>
          <TableBody>
            {allAdministrationPermissions.map((permission) => (
              <TableRow key={permission}>
                <TableCell><p className="font-medium">{administrationPermissionLabels[permission]}</p><p className="text-xs text-muted-foreground">{permissionDescriptions[permission]}</p></TableCell>
                {(["user", "admin"] as const).map((role) => <TableCell key={role} className="text-center"><Switch checked={form.find((entry) => entry.role === role)?.permissions.includes(permission) ?? false} onCheckedChange={(checked) => toggle(role, permission, checked)} aria-label={`${administrationPermissionLabels[permission]}: ${role}`} /></TableCell>)}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Alert><ShieldCheck /><AlertTitle>Zasada najmniejszych uprawnień</AlertTitle><AlertDescription>Zmiany dotyczą nowych sesji. Administrator nie może odebrać sam sobie prawa do zarządzania użytkownikami w docelowym systemie.</AlertDescription></Alert>
      <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "rolePermissions", form, "Zapisano uprawnienia ról")} />
    </SettingsCard>
  )
}

const permissionDescriptions: Record<AdministrationPermission, string> = {
  manage_users: "Dodawanie, edycja, dezaktywacja i usuwanie kont.",
  manage_integrations: "Konfiguracja połączeń oraz ignorowanych kanałów Slack, Teams i Telegram.",
  manage_sla: "Zmiana czasów i zasad naliczania SLA.",
  view_global_statistics: "Dostęp do statystyk całej organizacji.",
}

function SettingsCard({ title, description, actions, children }: { title: string; description: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between gap-4">
        <div><CardTitle>{title}</CardTitle><CardDescription>{description}</CardDescription></div>
        {actions}
      </CardHeader>
      <CardContent className="grid gap-4">{children}</CardContent>
    </Card>
  )
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="grid gap-1.5 text-sm"><span className="font-medium">{label}</span>{children}{hint && <span className="text-xs text-muted-foreground">{hint}</span>}</label>
}

function SettingSwitch({ label, description, checked, onChange }: { label: string; description: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return <div className="flex items-center justify-between gap-4 rounded-lg border p-3"><div><p className="text-sm font-medium">{label}</p><p className="text-xs text-muted-foreground">{description}</p></div><Switch checked={checked} onCheckedChange={onChange} /></div>
}

function EnabledState({ enabled }: { enabled: boolean }) {
  return <span className={`inline-flex items-center gap-1 text-xs ${enabled ? "text-success" : "text-muted-foreground"}`}>{enabled ? <CheckCircle2 className="size-3.5" /> : <Unplug className="size-3.5" />}{enabled ? "Włączone" : "Wyłączone"}</span>
}

function SaveButton({ pending, onClick }: { pending: boolean; onClick: () => void }) {
  return <div className="flex justify-end border-t pt-4"><Button onClick={onClick} disabled={pending}><Save /> {pending ? "Zapisywanie…" : "Zapisz zmiany"}</Button></div>
}

function TimelineItem({ label, value, accent }: { label: string; value: string; accent?: "warning" | "destructive" }) {
  return <div className="flex items-center gap-3"><span className={`size-2.5 rounded-full ${accent === "warning" ? "bg-warning" : accent === "destructive" ? "bg-destructive" : "bg-primary"}`} /><div className="min-w-0 flex-1"><p className="text-xs text-muted-foreground">{label}</p><p className="font-medium">{value}</p></div></div>
}

async function saveSection<K extends keyof AdministrationSettings>(actions: SettingsActions, key: K, value: AdministrationSettings[K], successMessage: string) {
  try {
    await actions.saveSection.mutateAsync({ key, value } as AdministrationSectionInput)
    notify.success(successMessage)
  } catch (error) {
    notify.error("Nie udało się zapisać zmian", getErrorMessage(error))
  }
}

function calculateSlaExample(settings: SlaSettings) {
  const start = new Date(2026, 7, 3, 16, 30)
  const add = (minutes: number) => settings.businessHoursOnly ? addBusinessMinutes(start, minutes) : new Date(start.getTime() + minutes * 60_000)
  const format = (date: Date) => new Intl.DateTimeFormat("pl-PL", { weekday: "short", hour: "2-digit", minute: "2-digit" }).format(date)
  return {
    warning: format(add(Math.max(0, settings.firstResponseMinutes - settings.warningBeforeDeadlineMinutes))),
    deadline: format(add(settings.firstResponseMinutes)),
  }
}

function addBusinessMinutes(start: Date, amount: number) {
  const date = new Date(start)
  let remaining = amount
  while (remaining > 0) {
    if (date.getDay() === 0 || date.getDay() === 6) {
      date.setDate(date.getDate() + (date.getDay() === 6 ? 2 : 1))
      date.setHours(8, 0, 0, 0)
      continue
    }
    if (date.getHours() < 8) date.setHours(8, 0, 0, 0)
    if (date.getHours() >= 17) {
      date.setDate(date.getDate() + 1)
      date.setHours(8, 0, 0, 0)
      continue
    }
    const closing = new Date(date)
    closing.setHours(17, 0, 0, 0)
    const available = Math.max(0, Math.round((closing.getTime() - date.getTime()) / 60_000))
    const consumed = Math.min(available, remaining)
    date.setMinutes(date.getMinutes() + consumed)
    remaining -= consumed
    if (remaining > 0) {
      date.setDate(date.getDate() + 1)
      date.setHours(8, 0, 0, 0)
    }
  }
  return date
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Spróbuj ponownie."
}
