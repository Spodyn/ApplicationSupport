"use client"

import { userError } from "@/lib/i18n/errors"
import { locale } from "@/lib/i18n/catalog"
import { translate as t } from "@/lib/i18n/catalog"
import { LanguageSelector, useT } from "@/lib/i18n/provider"
import { useState, type ReactNode } from "react"
import {
  BellRing,
  Building2,
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
  ChannelGroupingStrategy,
  GeneralSettings,
  ManagedChannel,
  ManagedCustomer,
  ManagedIntegration,
  NotificationDestination,
  NotificationDelivery,
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
  { value: "customers", label: "Klienci", icon: Building2 },
  { value: "channels", label: "Kanały", icon: Radio },
  { value: "notifications", label: "Powiadomienia", icon: BellRing },
  { value: "permissions", label: "Uprawnienia", icon: ShieldCheck },
] as const

export function AdministrationSettingsPage() {
  const t = useT()
  const settingsQuery = useAdministrationSettings()
  const actions = useAdministrationSettingsActions()
  const unavailableIntegrations =
    settingsQuery.data?.integrations.filter((item) => item.status !== "enabled") ?? []

  return (
    <>
      <PageHeader
        title={t("Ustawienia")}
        description={t("Zasady pracy skrzynki, integracje i uprawnienia zespołu")}
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={() => void settingsQuery.refetch()}
            disabled={settingsQuery.isFetching}
          >
            <RefreshCw className={settingsQuery.isFetching ? "animate-spin" : undefined} />
            <span className="hidden sm:inline">{t("Odśwież")}</span>
          </Button>
        }
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1500px] flex-col gap-4 p-4 md:p-6">
          <Alert className="bg-info/5 text-info">
            <Info />
            <AlertTitle>{t("Konfiguracja chroniona")}</AlertTitle>
            <AlertDescription>
              {t("Dane integracji, klientów i kanałów są zapisywane w backendzie. Sekrety Slack pozostają wyłącznie w zewnętrznym katalogu sekretów.")} </AlertDescription>
          </Alert>

          {unavailableIntegrations.length > 0 && (
            <Alert className="border-warning/30 bg-warning/10 text-warning-foreground">
              <Unplug />
              <AlertTitle>{t("Nie wszystkie integracje są włączone")}</AlertTitle>
              <AlertDescription>
                {unavailableIntegrations.map((item) => channelLabels[item.platform]).join(", ")} {t("— sprawdź status w zakładce Integracje. Odbiór nowych wiadomości z tych źródeł może być ograniczony.")} </AlertDescription>
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
                      <Icon /> {t(label)}
                    </TabsTrigger>
                  ))}
                </TabsList>
              </div>

              <TabsContent value="general"><GeneralPanel data={settingsQuery.data.general} actions={actions} /></TabsContent>
              <TabsContent value="sla"><SlaPanel data={settingsQuery.data.sla} actions={actions} /></TabsContent>
              <TabsContent value="schedule"><SchedulePanel data={settingsQuery.data.schedule} actions={actions} /></TabsContent>
              <TabsContent value="out-of-office"><OutOfOfficePanel data={settingsQuery.data.outOfOffice} actions={actions} /></TabsContent>
              <TabsContent value="integrations"><IntegrationsPanel data={settingsQuery.data.integrations} actions={actions} /></TabsContent>
              <TabsContent value="customers"><CustomersPanel data={settingsQuery.data.customers} actions={actions} /></TabsContent>
              <TabsContent value="channels"><ChannelsPanel data={settingsQuery.data.channels} customers={settingsQuery.data.customers} actions={actions} /></TabsContent>
              <TabsContent value="notifications"><NotificationsPanel data={settingsQuery.data.notifications} deliveries={settingsQuery.data.notificationDeliveries ?? []} integrations={settingsQuery.data.integrations} actions={actions} /></TabsContent>
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
  const t = useT()
  const [form, setForm] = useState(data)

  return (
    <SettingsCard title={t("Ogólne")} description={t("Podstawowe zachowanie i wygląd skrzynki.")}>
      <div className="grid max-w-2xl gap-4 sm:grid-cols-2">
        <Field label={t("Nazwa organizacji")}>
          <Input value={form.organizationName} onChange={(event) => setForm({ ...form, organizationName: event.target.value })} />
        </Field>
        <div className="self-end"><LanguageSelector /></div>
        <SettingSwitch label={t("Widok kompaktowy")} description={t("Zmniejsza odstępy w tabelach i listach.")} checked={form.compactMode} onChange={(checked) => setForm({ ...form, compactMode: checked })} />
      </div>
      <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "general", form, t("Zapisano ustawienia ogólne"))} />
    </SettingsCard>
  )
}

function SlaPanel({ data, actions }: { data: SlaSettings; actions: SettingsActions }) {
  const t = useT()
  const [form, setForm] = useState(data)
  const example = calculateSlaExample(form)

  const numberFields: { key: keyof Pick<SlaSettings, "firstResponseMinutes" | "unclaimedReminderMinutes" | "inProgressReminderMinutes" | "warningBeforeDeadlineMinutes" | "repeatedBreachMinutes">; label: string; hint: string }[] = [
    { key: "firstResponseMinutes", label: t("SLA pierwszej odpowiedzi"), hint: t("Czas od otrzymania do pierwszej odpowiedzi.") },
    { key: "unclaimedReminderMinutes", label: t("Przypomnienie o nieprzejętym case’ie"), hint: t("Pierwszy alert, gdy case nadal nie ma właściciela.") },
    { key: "inProgressReminderMinutes", label: t("Przypomnienie w trakcie"), hint: t("Alert o zbyt długiej pracy bez aktualizacji.") },
    { key: "warningBeforeDeadlineMinutes", label: t("Ostrzeżenie przed terminem"), hint: t("Kiedy case otrzymuje stan ostrzegawczy.") },
    { key: "repeatedBreachMinutes", label: t("Interwał ponawiania przekroczenia"), hint: t("Jak często ponawiać alert po przekroczeniu.") },
  ]

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <SettingsCard title={t("Polityka SLA")} description={t("Czasy podawane są w minutach i obowiązują wszystkie kanały z tą polityką.")}>
        <div className="grid gap-4 md:grid-cols-2">
          {numberFields.map((field) => (
            <Field key={field.key} label={field.label} hint={field.hint}>
              <div className="relative"><Input type="number" min={1} value={form[field.key]} onChange={(event) => setForm({ ...form, [field.key]: Math.max(1, Number(event.target.value)) })} className="pr-14" /><span className="absolute right-2.5 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">min</span></div>
            </Field>
          ))}
        </div>
        <div className="grid gap-2 border-t pt-4 sm:grid-cols-2">
          <SettingSwitch label={t("Tylko w godzinach pracy")} description={t("Odliczanie pomija zamknięte godziny i wyjątki.")} checked={form.businessHoursOnly} onChange={(checked) => setForm({ ...form, businessHoursOnly: checked })} />
          <SettingSwitch label={t("Wstrzymaj przy oczekiwaniu")} description={t("SLA nie biegnie podczas oczekiwania na klienta.")} checked={form.pauseWhileWaiting} onChange={(checked) => setForm({ ...form, pauseWhileWaiting: checked })} />
        </div>
        <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "sla", form, t("Zapisano politykę SLA"))} />
      </SettingsCard>

      <Card className="h-fit border-primary/20 bg-primary/[0.03]">
        <CardHeader><CardTitle className="flex items-center gap-2"><Clock3 className="size-4 text-primary" /> {t("Przykład na żywo")}</CardTitle><CardDescription>{t("Case otrzymany w poniedziałek o 16:30.")}</CardDescription></CardHeader>
        <CardContent className="grid gap-4">
          <TimelineItem label={t("Otrzymano")} value="pon., 16:30" />
          <TimelineItem label={t("Ostrzeżenie")} value={example.warning} accent="warning" />
          <TimelineItem label={t("Termin SLA")} value={example.deadline} accent="destructive" />
          <p className="rounded-lg bg-muted/60 p-3 text-xs text-muted-foreground">
            {form.businessHoursOnly ? t("Po 17:00 licznik zatrzymuje się i rusza ponownie o 08:00 następnego dnia roboczego.") : t("SLA liczone jest w sposób ciągły, również poza godzinami pracy.")}
          </p>
        </CardContent>
      </Card>
    </div>
  )
}

function SchedulePanel({ data, actions }: { data: WorkScheduleSettings; actions: SettingsActions }) {
  const t = useT()
  const [form, setForm] = useState(data)
  const [exceptionOpen, setExceptionOpen] = useState(false)
  const [exception, setException] = useState<Omit<ScheduleException, "id">>({ date: "", name: "", closed: true })

  const updateDay = (index: number, patch: Partial<WorkScheduleSettings["days"][number]>) => {
    setForm((current) => ({ ...current, days: current.days.map((day, dayIndex) => dayIndex === index ? { ...day, ...patch } : day) }))
  }

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
      <SettingsCard title={t("Tygodniowy harmonogram")} description={t("Godziny używane przez SLA, automatyczne odpowiedzi i przypomnienia.")}>
        <Field label={t("Strefa czasowa")}>
          <Select value={form.timezone} onValueChange={(value) => setForm({ ...form, timezone: String(value) })}>
            <SelectTrigger className="w-full sm:w-72"><SelectValue /></SelectTrigger>
            <SelectContent><SelectItem value="Europe/Warsaw">Europe/Warsaw (CET/CEST)</SelectItem><SelectItem value="Europe/London">Europe/London</SelectItem><SelectItem value="UTC">UTC</SelectItem></SelectContent>
          </Select>
        </Field>
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>{t("Dzień")}</TableHead><TableHead>{t("Otwarte")}</TableHead><TableHead>{t("Godziny")}</TableHead><TableHead>{t("Przerwa")}</TableHead><TableHead>{t("Godziny przerwy")}</TableHead></TableRow></TableHeader>
            <TableBody>
              {form.days.map((day, index) => (
                <TableRow key={day.key}>
                  <TableCell className="font-medium">{t(day.label)}</TableCell>
                  <TableCell><Switch checked={day.enabled} onCheckedChange={(checked) => updateDay(index, { enabled: checked })} aria-label={t("{{value0}}: otwarte", { value0: t(day.label) })} /></TableCell>
                  <TableCell><div className="flex items-center gap-1.5"><Input type="time" value={day.start} disabled={!day.enabled} onChange={(event) => updateDay(index, { start: event.target.value })} className="w-28" /><span className="text-muted-foreground">–</span><Input type="time" value={day.end} disabled={!day.enabled} onChange={(event) => updateDay(index, { end: event.target.value })} className="w-28" /></div></TableCell>
                  <TableCell><Switch checked={day.breakEnabled} disabled={!day.enabled} onCheckedChange={(checked) => updateDay(index, { breakEnabled: checked })} aria-label={t("{{value0}}: przerwa", { value0: t(day.label) })} /></TableCell>
                  <TableCell><div className="flex items-center gap-1.5"><Input type="time" value={day.breakStart} disabled={!day.enabled || !day.breakEnabled} onChange={(event) => updateDay(index, { breakStart: event.target.value })} className="w-28" /><span className="text-muted-foreground">–</span><Input type="time" value={day.breakEnd} disabled={!day.enabled || !day.breakEnabled} onChange={(event) => updateDay(index, { breakEnd: event.target.value })} className="w-28" /></div></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
        <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "schedule", form, t("Zapisano harmonogram pracy"))} />
      </SettingsCard>

      <SettingsCard title={t("Święta i wyjątki")} description={t("Daty, w których standardowy harmonogram nie obowiązuje.")} actions={<Button variant="outline" size="sm" onClick={() => setExceptionOpen(true)}><Plus /> {t("Dodaj")}</Button>}>
        <div className="grid gap-2">
          {form.exceptions.map((item) => (
            <div key={item.id} className="flex items-center gap-3 rounded-lg border p-3">
              <div className="flex size-9 items-center justify-center rounded-lg bg-muted"><CalendarClock className="size-4" /></div>
              <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium">{item.nameIsDefault ? t(item.name) : item.name}</p><p className="text-xs text-muted-foreground">{formatDate(item.date)} · {item.closed ? t("zamknięte") : `${item.start}–${item.end}`}</p></div>
              <Button variant="ghost" size="icon-sm" aria-label={t("Usuń {{value0}}", { value0: item.name })} onClick={() => setForm({ ...form, exceptions: form.exceptions.filter((entry) => entry.id !== item.id) })}><Trash2 /></Button>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">{t("Usunięcia zostaną zastosowane po zapisaniu harmonogramu.")}</p>
      </SettingsCard>

      <Dialog open={exceptionOpen} onOpenChange={setExceptionOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{t("Dodaj wyjątek")}</DialogTitle><DialogDescription>{t("Ustaw dzień zamknięty lub niestandardowe godziny pracy.")}</DialogDescription></DialogHeader>
          <div className="grid gap-3">
            <Field label={t("Nazwa")}><Input value={exception.name} onChange={(event) => setException({ ...exception, name: event.target.value })} /></Field>
            <Field label={t("Data")}><Input type="date" value={exception.date} onChange={(event) => setException({ ...exception, date: event.target.value })} /></Field>
            <SettingSwitch label={t("Dzień zamknięty")} description={t("W tym dniu SLA nie będzie naliczane.")} checked={exception.closed} onChange={(checked) => setException({ ...exception, closed: checked })} />
            {!exception.closed && <div className="grid grid-cols-2 gap-3"><Field label={t("Od")}><Input type="time" value={exception.start ?? "08:00"} onChange={(event) => setException({ ...exception, start: event.target.value })} /></Field><Field label={t("Do")}><Input type="time" value={exception.end ?? "16:00"} onChange={(event) => setException({ ...exception, end: event.target.value })} /></Field></div>}
          </div>
          <DialogFooter><DialogClose render={<Button variant="outline" />}>{t("Anuluj")}</DialogClose><Button onClick={() => { if (!exception.date || !exception.name.trim()) { notify.warning(t("Uzupełnij nazwę i datę")); return }; setForm({ ...form, exceptions: [...form.exceptions, { ...exception, id: `ex-${Date.now()}` }] }); setExceptionOpen(false); setException({ date: "", name: "", closed: true }); notify.success(t("Dodano wyjątek"), t("Zapisz harmonogram, aby utrwalić zmianę.")) }}>{t("Dodaj wyjątek")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

const placeholders = ["customer_name", "next_opening_date", "next_opening_time", "timezone"] as const

function OutOfOfficePanel({ data, actions }: { data: OutOfOfficeSettings; actions: SettingsActions }) {
  const t = useT()
  const [form, setForm] = useState(data)
  const preview = (form.template === data.template && data.previewMessage ? data.previewMessage : form.template)
    .replaceAll("{{customer_name}}", "Joanno")
    .replaceAll("{{next_opening_date}}", t("4 sierpnia 2026"))
    .replaceAll("{{next_opening_time}}", "08:00")
    .replaceAll("{{timezone}}", "Europe/Warsaw")

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_420px]">
      <SettingsCard title={t("Poza biurem")} description={t("Automatyczna odpowiedź wysyłana poza harmonogramem pracy.")}>
        <SettingSwitch label={t("Włącz automatyczną odpowiedź")} description={t("Dotyczy wszystkich włączonych kanałów.")} checked={form.enabled} onChange={(checked) => setForm({ ...form, enabled: checked })} />
        <Field label={t("Szablon wiadomości")} hint={t("Kliknij zmienną, aby dodać ją na końcu treści.")}>
          <textarea value={form.template} onChange={(event) => setForm({ ...form, template: event.target.value })} rows={7} className="w-full resize-y rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30" />
        </Field>
        <div className="flex flex-wrap gap-2">
          {placeholders.map((placeholder) => <Button key={placeholder} variant="secondary" size="xs" onClick={() => setForm({ ...form, template: `${form.template}${form.template.endsWith(" ") ? "" : " "}{{${placeholder}}}` })}>{`{{${placeholder}}}`}</Button>)}
        </div>
        <SettingSwitch label={t("Wyślij raz na okres zamknięcia")} description={t("Klient nie otrzyma wielu identycznych wiadomości przed kolejnym otwarciem.")} checked={form.sendOncePerClosure} onChange={(checked) => setForm({ ...form, sendOncePerClosure: checked })} />
        <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "outOfOffice", form, t("Zapisano ustawienia nieobecności"))} />
      </SettingsCard>
      <Card className="h-fit"><CardHeader><CardTitle className="flex items-center gap-2"><MessageSquareText className="size-4 text-primary" /> {t("Podgląd")}</CardTitle><CardDescription>{t("Przykładowa wiadomość dla klienta.")}</CardDescription></CardHeader><CardContent><div className="rounded-2xl rounded-bl-sm bg-muted p-4 text-sm leading-relaxed">{preview}</div><p className="mt-3 text-xs text-muted-foreground">{t("Zmienne zostaną uzupełnione na podstawie klienta i aktywnego harmonogramu.")}</p></CardContent></Card>
    </div>
  )
}

const integrationStatusLabels: Record<ManagedIntegration["status"], string> = {
  configuring: "Konfigurowana",
  enabled: "Włączona",
  disabled: "Wyłączona",
}

const integrationHealthLabels: Record<ManagedIntegration["health"], string> = {
  unknown: "Nie sprawdzono",
  healthy: "Połączenie sprawne",
  degraded: "Wymaga uwagi",
  unavailable: "Brak połączenia",
}

function IntegrationsPanel({ data, actions }: { data: ManagedIntegration[]; actions: SettingsActions }) {
  const t = useT()
  const [configured, setConfigured] = useState<ManagedIntegration | null>(null)
  const [displayName, setDisplayName] = useState("Slack")
  const [workspaceName, setWorkspaceName] = useState("")
  const [workspaceId, setWorkspaceId] = useState("")
  const [secretRef, setSecretRef] = useState("")
  const [disabledIntegration, setDisabledIntegration] = useState<ManagedIntegration | null>(null)

  const openConfiguration = (integration?: ManagedIntegration) => {
    setDisplayName(integration?.displayName ?? "Slack")
    setWorkspaceName(integration?.workspace ?? "")
    setWorkspaceId(integration?.workspaceId ?? "")
    setSecretRef("")
    if (!integration) {
      setConfigured({ id: "", platform: "slack", displayName: "Slack", status: "configuring", workspace: "", workspaceId: "", hasSecretReference: false, health: "unknown" })
      return
    }
    setConfigured(integration)
  }

  const test = async (integration: ManagedIntegration) => {
    try {
      await actions.testIntegration.mutateAsync(integration.id)
      notify.success(t("Test połączenia zakończony powodzeniem"), channelLabels[integration.platform])
    } catch (error) {
      notify.error(t("Test połączenia nie powiódł się"), getErrorMessage(error))
    }
  }

  const completeConfiguration = async (integration: ManagedIntegration) => {
    try {
      await actions.setIntegrationStatus.mutateAsync({ id: integration.id, status: "enabled" })
      notify.success(t("Integracja została włączona"), channelLabels[integration.platform])
    } catch (error) {
      notify.error(t("Nie udało się włączyć integracji"), getErrorMessage(error))
    }
  }

  const discover = async (integration: ManagedIntegration) => {
    try {
      const channels = await actions.discoverChannels.mutateAsync(integration.id)
      notify.success(t("Odświeżono kanały Slack"), t("Znaleziono {{value0}} kanałów dostępnych dla aplikacji.", { value0: channels.length }))
    } catch (error) {
      notify.error(t("Nie udało się pobrać kanałów Slack"), getErrorMessage(error))
    }
  }

  return (
    <div className="grid gap-4">
      <Alert className="border-warning/30 bg-warning/10 text-warning-foreground">
        <TriangleAlert />
        <AlertTitle>{t("Zakres Microsoft Teams")}</AlertTitle>
        <AlertDescription>
          {t("Początkowo obsługiwane są standardowe kanały i czaty grupowe z RSC. Kanały prywatne i udostępnione wymagają osobnej walidacji.")} </AlertDescription>
      </Alert>

      <div className="flex justify-end">
        <Button size="sm" onClick={() => openConfiguration()}><Plus /> {t("Dodaj Slack")}</Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        {data.map((integration) => (
          <Card key={integration.id} className="min-w-0">
            <CardHeader>
              <div className="flex items-start gap-3">
                <PlatformIcon channel={integration.platform} className="size-10" />
                <div className="min-w-0 flex-1">
                  <CardTitle>{channelLabels[integration.platform]}</CardTitle>
                  <CardDescription className="truncate">{integration.workspace || t("Nie skonfigurowano")}</CardDescription>
                </div>
                <Badge
                  variant="secondary"
                  className={
                    integration.status === "enabled"
                      ? "bg-success/10 text-success"
                      : integration.status === "configuring"
                        ? "bg-warning/15 text-warning-foreground"
                        : "text-muted-foreground"
                  }
                >
                  {integration.status === "enabled" ? <CheckCircle2 /> : integration.status === "configuring" ? <TriangleAlert /> : <Unplug />}
                  {t(integrationStatusLabels[integration.status])}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="grid gap-4">
              <dl className="grid gap-3 text-xs">
                <div className="flex items-start justify-between gap-3"><dt className="text-muted-foreground">{t("Obszar roboczy / dzierżawa")}</dt><dd className="max-w-48 truncate text-right font-mono">{integration.workspace || "—"}</dd></div>
                <div className="flex items-start justify-between gap-3"><dt className="text-muted-foreground">{t("Ostatnie zdarzenie")}</dt><dd className="text-right">{integration.lastEventAt ? formatDateTime(integration.lastEventAt) : t("Brak")}</dd></div>
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-muted-foreground">{t("Health check")}</dt>
                  <dd className="flex items-center gap-1.5">
                    {integration.health === "healthy"
                      ? <CheckCircle2 className="size-3.5 text-success" />
                      : integration.health === "degraded"
                        ? <TriangleAlert className="size-3.5 text-warning-foreground" />
                        : integration.health === "unknown"
                          ? <Info className="size-3.5 text-muted-foreground" />
                          : <Unplug className="size-3.5 text-destructive" />}
                    {t(integrationHealthLabels[integration.health])}
                  </dd>
                </div>
              </dl>
              <div className="flex flex-wrap gap-2 border-t pt-3">
                <Button variant="outline" size="sm" onClick={() => openConfiguration(integration)}><Settings2 /> {t("Konfiguruj")}</Button>
                <Button variant="outline" size="sm" disabled={integration.status === "disabled" || !integration.hasSecretReference || actions.testIntegration.isPending} onClick={() => void test(integration)}><PlugZap /> {t("Testuj")}</Button>
                {integration.platform === "slack" && <Button variant="outline" size="sm" disabled={integration.status === "disabled" || integration.health !== "healthy" || actions.discoverChannels.isPending} onClick={() => void discover(integration)}><RefreshCw /> {t("Pobierz kanały")}</Button>}
                {integration.status === "configuring" && integration.health === "healthy" && <Button size="sm" onClick={() => void completeConfiguration(integration)}><CheckCircle2 /> {t("Włącz")}</Button>}
                {integration.status === "disabled" && <Button size="sm" disabled={!integration.hasSecretReference || actions.setIntegrationStatus.isPending} onClick={() => void completeConfiguration(integration)}><CheckCircle2 /> {t("Włącz")}</Button>}
                {integration.status !== "disabled" && <Button variant="destructive" size="sm" onClick={() => setDisabledIntegration(integration)}><Unplug /> {t("Wyłącz")}</Button>}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Dialog open={Boolean(configured)} onOpenChange={(open) => !open && setConfigured(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{configured?.id ? t("Konfiguruj Slack") : t("Dodaj Slack")}</DialogTitle><DialogDescription>{t("Bezpieczny tryb konfiguracji przez zewnętrzną referencję sekretu. Wartości tokenu i signing secretu nigdy nie trafiają do przeglądarki ani bazy danych.")}</DialogDescription></DialogHeader>
          <div className="grid gap-4">
            <Field label={t("Nazwa połączenia")}><Input value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder={t("Slack — Support")} /></Field>
            <Field label={t("Nazwa workspace")}><Input value={workspaceName} onChange={(event) => setWorkspaceName(event.target.value)} placeholder={t("Firma Support")} /></Field>
            <Field label={t("Slack Workspace ID")} hint={t("Identyfikator team_id zwracany przez Slack, np. T012ABCDEF.")}><Input value={workspaceId} onChange={(event) => setWorkspaceId(event.target.value)} placeholder="T012ABCDEF" /></Field>
            <Field label={t("Referencja sekretu")} hint={t("Względny katalog pod USI_INTEGRATION_SECRETS_DIRECTORY.")}><Input value={secretRef} onChange={(event) => setSecretRef(event.target.value)} placeholder="slack/firma-support" /></Field>
          </div>
          <Alert><Info /><AlertDescription>{t("W tym katalogu muszą istnieć pliki")} <code>slack-bot-token</code> {t("i")} <code>slack-signing-secret</code>{t(". Po zapisie uruchom test, pobierz kanały i włącz integrację.")}</AlertDescription></Alert>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>{t("Anuluj")}</DialogClose>
            <Button disabled={!displayName.trim() || !workspaceName.trim() || !workspaceId.trim() || !secretRef.trim() || actions.configureIntegration.isPending} onClick={async () => {
              if (!configured) return
              try {
                await actions.configureIntegration.mutateAsync({ id: configured.id || undefined, platform: "slack", displayName, workspaceId, workspaceName, secretRef })
                notify.success(t("Zapisano konfigurację"), "Slack")
                setConfigured(null)
              } catch (error) { notify.error(t("Nie udało się zapisać konfiguracji"), getErrorMessage(error)) }
            }}>{actions.configureIntegration.isPending ? t("Zapisywanie…") : t("Zapisz konfigurację")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={Boolean(disabledIntegration)}
        onOpenChange={(open) => !open && setDisabledIntegration(null)}
        title={t("Wyłączyć integrację?")}
        description={disabledIntegration ? t("Nowe zdarzenia z {{value0}} nie będą tworzyły nowych spraw. Istniejące case’y pozostaną dostępne.", { value0: channelLabels[disabledIntegration.platform] }) : undefined}
        confirmLabel={t("Wyłącz")}
        destructive
        onConfirm={() => {
          if (!disabledIntegration) return
          const integration = disabledIntegration
          void actions.setIntegrationStatus.mutateAsync({ id: integration.id, status: "disabled" }).then(() => {
            notify.success(t("Integracja została wyłączona"), channelLabels[integration.platform])
            setDisabledIntegration(null)
          }).catch((error) => notify.error(t("Nie udało się wyłączyć integracji"), getErrorMessage(error)))
        }}
      />
    </div>
  )
}

function CustomersPanel({ data, actions }: { data: ManagedCustomer[]; actions: SettingsActions }) {
  const t = useT()
  const [edited, setEdited] = useState<ManagedCustomer | null>(null)
  const [name, setName] = useState("")
  const [externalRef, setExternalRef] = useState("")
  const [deactivated, setDeactivated] = useState<ManagedCustomer | null>(null)

  const open = (customer?: ManagedCustomer) => {
    setEdited(customer ?? { id: "", name: "", active: true })
    setName(customer?.name ?? "")
    setExternalRef(customer?.externalRef ?? "")
  }

  return (
    <SettingsCard title={t("Klienci")} description={t("Klienci przypisywani do monitorowanych kanałów i spraw.")} actions={<Button size="sm" onClick={() => open()}><Plus /> {t("Dodaj klienta")}</Button>}>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>{t("Nazwa")}</TableHead><TableHead>{t("Identyfikator zewnętrzny")}</TableHead><TableHead>{t("Status")}</TableHead><TableHead><span className="sr-only">{t("Akcje")}</span></TableHead></TableRow></TableHeader>
          <TableBody>{data.map((customer) => <TableRow key={customer.id}><TableCell className="font-medium">{customer.name}</TableCell><TableCell className="font-mono text-xs">{customer.externalRef ?? "—"}</TableCell><TableCell><EnabledState enabled={customer.active} /></TableCell><TableCell className="text-right"><Button variant="ghost" size="icon-sm" aria-label={t("Edytuj {{name}}", { name: customer.name })} onClick={() => open(customer)}><Pencil /></Button>{customer.active && <Button variant="ghost" size="icon-sm" aria-label={t("Dezaktywuj {{name}}", { name: customer.name })} onClick={() => setDeactivated(customer)}><Unplug /></Button>}</TableCell></TableRow>)}</TableBody>
        </Table>
      </div>
      <Dialog open={Boolean(edited)} onOpenChange={(value) => !value && setEdited(null)}>
        <DialogContent><DialogHeader><DialogTitle>{edited?.id ? t("Edytuj klienta") : t("Dodaj klienta")}</DialogTitle><DialogDescription>{t("Nazwa jest widoczna w skrzynce. Identyfikator zewnętrzny jest opcjonalny.")}</DialogDescription></DialogHeader><Field label={t("Nazwa")}><Input value={name} onChange={(event) => setName(event.target.value)} /></Field><Field label={t("Identyfikator zewnętrzny")}><Input value={externalRef} onChange={(event) => setExternalRef(event.target.value)} /></Field><DialogFooter><DialogClose render={<Button variant="outline" />}>{t("Anuluj")}</DialogClose><Button disabled={!name.trim() || actions.saveCustomer.isPending} onClick={() => { if (!edited) return; void actions.saveCustomer.mutateAsync({ id: edited.id || undefined, name, externalRef: externalRef.trim() || undefined }).then(() => { notify.success(t("Zapisano klienta"), name); setEdited(null) }).catch((error) => notify.error(t("Nie udało się zapisać klienta"), getErrorMessage(error))) }}>{t("Zapisz")}</Button></DialogFooter></DialogContent>
      </Dialog>
      <ConfirmDialog open={Boolean(deactivated)} onOpenChange={(value) => !value && setDeactivated(null)} title={t("Dezaktywować klienta?")} description={deactivated ? t("Klient „{{value0}}” nie będzie dostępny do nowych przypisań.", { value0: deactivated.name }) : undefined} confirmLabel={t("Dezaktywuj")} destructive onConfirm={() => { if (!deactivated) return; void actions.deactivateCustomer.mutateAsync(deactivated.id).then(() => { notify.success(t("Klient został dezaktywowany"), deactivated.name); setDeactivated(null) }).catch((error) => notify.error(t("Nie udało się dezaktywować klienta"), getErrorMessage(error))) }} />
    </SettingsCard>
  )
}

function ChannelsPanel({ data, customers, actions }: { data: ManagedChannel[]; customers: ManagedCustomer[]; actions: SettingsActions }) {
  const t = useT()
  const configure = async (channel: ManagedChannel, customerId: string | undefined, ignored: boolean) => {
    try {
      await actions.configureChannel.mutateAsync({ id: channel.id, customerId, ignored, groupingStrategy: channel.groupingStrategy })
      notify.success(t("Zapisano kanał"), channel.channelName)
    } catch (error) {
      notify.error(t("Nie udało się zapisać kanału"), getErrorMessage(error))
    }
  }

  return (
    <SettingsCard title={t("Kanały Slack")} description={t("Przypisz dostępne kanały do klientów i określ regułę monitorowania.")}>
      <Alert>
        <Info />
        <AlertTitle>{t("Wiadomości bez case’a i SLA")}</AlertTitle>
        <AlertDescription>{t("Kanały ignorowane nie tworzą nowych spraw. Wiadomości Slack bez przypisanego klienta trafiają do skrzynki jako „Nie przypisano”.")}</AlertDescription>
      </Alert>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>{t("Platforma")}</TableHead><TableHead>{t("Kanał")}</TableHead><TableHead>{t("Klient")}</TableHead><TableHead>{t("Stan")}</TableHead><TableHead>{t("Grupowanie")}</TableHead><TableHead>{t("Ostatnia wiadomość")}</TableHead></TableRow></TableHeader>
          <TableBody>
            {data.map((channel) => (
              <TableRow key={channel.id}>
                <TableCell><div className="flex items-center gap-2"><PlatformIcon channel={channel.platform} /> <span className="text-xs">{channelLabels[channel.platform]}</span></div></TableCell>
                <TableCell className="font-medium">{channel.channelName} · {channel.active ? t("Aktywny") : t("Nieaktywny")}</TableCell>
                <TableCell><Select value={channel.customerId ?? "__unmapped__"} onValueChange={(value) => void configure(channel, value === "__unmapped__" ? undefined : String(value), channel.ignored)}><SelectTrigger className="w-52"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="__unmapped__">{t("Nie przypisano")}</SelectItem>{customers.filter((customer) => customer.active).map((customer) => <SelectItem key={customer.id} value={customer.id}>{customer.name}</SelectItem>)}</SelectContent></Select></TableCell>
                <TableCell><div className="flex items-center gap-2"><Switch checked={!channel.ignored} onCheckedChange={(checked) => void configure(channel, channel.customerId, !checked)} aria-label={`${channel.channelName}: monitoring`} /><span className={`text-xs ${channel.ignored || !channel.customerId ? "text-warning-foreground" : "text-success"}`}>{!channel.active ? t("Nieaktywny") : channel.ignored ? t("Ignorowany") : !channel.customerId ? t("Nieprzypisany") : t("Monitorowany")}</span></div></TableCell>
                <TableCell className="font-mono text-xs">{channel.platform === "telegram" ? <Select value={channel.groupingStrategy} onValueChange={(value) => { if (channel.customerId) void actions.configureChannel.mutateAsync({ id: channel.id, customerId: channel.customerId, ignored: channel.ignored, groupingStrategy: value as ChannelGroupingStrategy }).catch((error) => notify.error(t("Nie udało się zapisać grupowania"), getErrorMessage(error))) }}><SelectTrigger aria-label={t("{{value0}}: grupowanie", { value0: channel.channelName })}><SelectValue /></SelectTrigger><SelectContent><SelectItem value="TELEGRAM_TOPIC">{t("Temat forum")}</SelectItem><SelectItem value="TELEGRAM_CHAT_ACTIVE_CASE">{t("Aktywny case na czat")}</SelectItem></SelectContent></Select> : channel.groupingStrategy === "SLACK_ROOT_THREAD" ? t("Root + wątek") : channel.groupingStrategy}</TableCell>
                <TableCell className="text-xs text-muted-foreground">{channel.lastMessageAt ? formatDateTime(channel.lastMessageAt) : t("Brak")}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <p className="text-xs text-muted-foreground">{t("Zmiana dotyczy nowych wiadomości przychodzących po zapisaniu reguły.")}</p>
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

function NotificationsPanel({ data, deliveries, integrations, actions }: { data: NotificationDestination[]; deliveries: NotificationDelivery[]; integrations: ManagedIntegration[]; actions: SettingsActions }) {
  const t = useT()
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
    } catch (error) { notify.error(t("Nie udało się zapisać powiadomień"), getErrorMessage(error)) }
  }

  const toggle = async (item: NotificationDestination, enabled: boolean) => {
    try {
      await actions.toggleNotification.mutateAsync({ id: item.id, enabled })
      notify.success(enabled ? t("Powiadomienia włączone") : t("Powiadomienia wyłączone"), item.name)
    } catch (error) { notify.error(t("Nie udało się zmienić powiadomień"), getErrorMessage(error)) }
  }

  const replay = async (delivery: NotificationDelivery) => {
    try {
      await actions.replayNotificationDelivery.mutateAsync(delivery.id)
      notify.success(t("Dostawa została ponownie zakolejkowana"))
    } catch (error) { notify.error(t("Nie udało się ponowić dostawy"), getErrorMessage(error)) }
  }

  return (
    <SettingsCard title={t("Cele powiadomień")} description={t("Alerty operacyjne wysyłane do wybranych kanałów.")} actions={<Button size="sm" onClick={openCreate}><Plus /> {t("Dodaj cel")}</Button>}>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>{t("Nazwa")}</TableHead><TableHead>{t("Dostawca")}</TableHead><TableHead>{t("Integracja")}</TableHead><TableHead>{t("Kanał")}</TableHead><TableHead className="min-w-80">{t("Typy")}</TableHead><TableHead>{t("Włączone")}</TableHead><TableHead><span className="sr-only">{t("Akcje")}</span></TableHead></TableRow></TableHeader>
          <TableBody>
            {data.map((item) => (
              <TableRow key={item.id}>
                <TableCell className="font-medium">{item.name}</TableCell>
                <TableCell><div className="flex items-center gap-2"><PlatformIcon channel={item.provider} /> {channelLabels[item.provider]}</div></TableCell>
                <TableCell className="font-mono text-xs">{integrations.find((integration) => integration.id === item.integrationId)?.workspace ?? "—"}</TableCell>
                <TableCell>{item.channelName}</TableCell>
                <TableCell><div className="flex max-w-96 flex-wrap gap-1">{item.types.map((type) => <Badge key={type} variant="secondary">{t(notificationTypeLabels[type])}</Badge>)}</div></TableCell>
                <TableCell><div className="flex items-center gap-2"><Switch checked={item.enabled} onCheckedChange={(checked) => void toggle(item, checked)} aria-label={`${item.name}: ${item.enabled ? t("włączone") : t("wyłączone")}`} /><EnabledState enabled={item.enabled} /></div></TableCell>
                <TableCell>
                  <Button variant="ghost" size="icon-sm" aria-label={t("Edytuj {{name}}", { name: item.name })} onClick={() => openEdit(item)}><Pencil /></Button>
                  <Button variant="ghost" size="icon-sm" aria-label={t("Usuń {{value0}}", { value0: item.name })} onClick={() => setRemoved(item)}><Trash2 /></Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-lg">
          <DialogHeader><DialogTitle>{edited?.id ? t("Edytuj cel powiadomień") : t("Dodaj cel powiadomień")}</DialogTitle><DialogDescription>{t("Wybierz integrację, kanał docelowy i zdarzenia generujące alert.")}</DialogDescription></DialogHeader>
          {edited && <div className="grid gap-4">
            <Field label={t("Nazwa")}><Input value={edited.name} onChange={(event) => setEdited({ ...edited, name: event.target.value })} /></Field>
            <Field label={t("Integracja")}>
              <Select value={edited.integrationId} onValueChange={(value) => { const integration = integrations.find((item) => item.id === String(value)); if (integration) setEdited({ ...edited, integrationId: integration.id, provider: integration.platform }) }}>
                <SelectTrigger className="w-full"><SelectValue /></SelectTrigger><SelectContent>{integrations.map((integration) => <SelectItem key={integration.id} value={integration.id}>{channelLabels[integration.platform]} · {integration.workspace}</SelectItem>)}</SelectContent>
              </Select>
            </Field>
            <Field label={t("Kanał docelowy")}><Input value={edited.channelName} onChange={(event) => setEdited({ ...edited, channelName: event.target.value })} placeholder="#support-alerts" /></Field>
            <fieldset className="grid gap-2 rounded-lg border p-3"><legend className="px-1 text-sm font-medium">{t("Typy powiadomień")}</legend>{(Object.entries(notificationTypeLabels) as [NotificationType, string][]).map(([type, label]) => <label key={type} className="flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-primary" checked={edited.types.includes(type)} onChange={(event) => setEdited({ ...edited, types: event.target.checked ? [...edited.types, type] : edited.types.filter((item) => item !== type) })} />{t(label)}</label>)}</fieldset>
            <SettingSwitch label={t("Cel aktywny")} description={t("Wyłączony cel nie otrzymuje żadnych alertów.")} checked={edited.enabled} onChange={(enabled) => setEdited({ ...edited, enabled })} />
          </div>}
          <DialogFooter><DialogClose render={<Button variant="outline" />}>{t("Anuluj")}</DialogClose><Button disabled={!edited?.name.trim() || !edited.channelName.trim() || edited.types.length === 0 || actions.saveSection.isPending} onClick={() => { if (!edited) return; const saved = { ...edited, id: edited.id || `not-${Date.now()}` }; const next = edited.id ? data.map((item) => item.id === edited.id ? saved : item) : [...data, saved]; void saveDestinations(next, edited.id ? t("Zapisano cel powiadomień") : t("Dodano cel powiadomień")) }}>{t("Zapisz")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog open={Boolean(removed)} onOpenChange={(open) => !open && setRemoved(null)} title={t("Usunąć cel powiadomień?")} description={removed ? t("Alerty nie będą już wysyłane do „{{value0}}”.", { value0: removed.name }) : undefined} confirmLabel={t("Usuń")} destructive onConfirm={() => { if (removed) void saveDestinations(data.filter((item) => item.id !== removed.id), t("Usunięto cel powiadomień")) }} />

      <div className="mt-6 grid gap-2">
        <div><h3 className="text-sm font-semibold">{t("Ostatnie dostawy")}</h3><p className="text-xs text-muted-foreground">{t("Statusy, próby i bezpieczne kody błędów; treści wiadomości oraz sekrety nie są wyświetlane.")}</p></div>
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader><TableRow className="bg-muted/40"><TableHead>{t("Zdarzenie")}</TableHead><TableHead>{t("Cel")}</TableHead><TableHead>{t("Status")}</TableHead><TableHead>{t("Próby")}</TableHead><TableHead>{t("Utworzono")}</TableHead><TableHead>{t("Błąd")}</TableHead><TableHead><span className="sr-only">{t("Akcja")}</span></TableHead></TableRow></TableHeader>
            <TableBody>{deliveries.map((delivery) => <TableRow key={delivery.id}>
              <TableCell className="font-mono text-xs">{delivery.eventType}</TableCell>
              <TableCell>{delivery.targetRef}</TableCell>
              <TableCell><Badge variant={delivery.status === "SENT" ? "secondary" : delivery.status === "DLQ" ? "destructive" : "outline"}>{t(delivery.status)}</Badge></TableCell>
              <TableCell className="tabular-nums">{delivery.attempts}</TableCell>
              <TableCell className="text-xs text-muted-foreground">{formatDateTime(delivery.createdAt)}</TableCell>
              <TableCell className="font-mono text-xs">{delivery.lastErrorCode ?? delivery.terminalReason ?? "—"}</TableCell>
              <TableCell><Button size="sm" variant="outline" disabled={delivery.status !== "DLQ" || actions.replayNotificationDelivery.isPending} onClick={() => void replay(delivery)}><RefreshCw /> {t("Ponów")}</Button></TableCell>
            </TableRow>)}</TableBody>
          </Table>
        </div>
      </div>
    </SettingsCard>
  )
}

function PermissionsPanel({ data, actions }: { data: RolePermissions[]; actions: SettingsActions }) {
  const t = useT()
  const [form, setForm] = useState(data)

  const toggle = (role: RolePermissions["role"], permission: AdministrationPermission, checked: boolean) => {
    setForm((current) => current.map((item) => item.role !== role ? item : {
      ...item,
      permissions: checked ? [...new Set([...item.permissions, permission])] : item.permissions.filter((entry) => entry !== permission),
    }))
  }

  return (
    <SettingsCard title={t("Uprawnienia ról")} description={t("Granularny zakres funkcji administracyjnych. Rola Użytkownik nie otrzymuje uprawnień administracyjnych.")}>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader><TableRow className="bg-muted/40"><TableHead>{t("Uprawnienie")}</TableHead><TableHead className="w-32 text-center">{t("Użytkownik")}</TableHead><TableHead className="w-32 text-center">{t("Administrator")}</TableHead></TableRow></TableHeader>
          <TableBody>
            {allAdministrationPermissions.map((permission) => (
              <TableRow key={permission}>
                <TableCell><p className="font-medium">{t(administrationPermissionLabels[permission])}</p><p className="text-xs text-muted-foreground">{t(permissionDescriptions[permission])}</p></TableCell>
                {(["USER", "ADMIN"] as const).map((role) => <TableCell key={role} className="text-center"><Switch checked={form.find((entry) => entry.role === role)?.permissions.includes(permission) ?? false} disabled={role === "USER"} onCheckedChange={(checked) => toggle(role, permission, checked)} aria-label={`${t(administrationPermissionLabels[permission])}: ${role}`} /></TableCell>)}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Alert><ShieldCheck /><AlertTitle>{t("Zasada najmniejszych uprawnień")}</AlertTitle><AlertDescription>{t("Zmiany dotyczą nowych sesji. Administrator nie może odebrać sam sobie prawa do zarządzania użytkownikami w docelowym systemie.")}</AlertDescription></Alert>
      <SaveButton pending={actions.saveSection.isPending} onClick={() => saveSection(actions, "rolePermissions", form, t("Zapisano uprawnienia ról"))} />
    </SettingsCard>
  )
}

const permissionDescriptions: Record<AdministrationPermission, string> = {
  manage_users: "Dodawanie, edycja, dezaktywacja i usuwanie kont.",
  manage_integrations: "Konfiguracja połączeń oraz ignorowanych kanałów Slack, Teams i Telegram.",
  manage_sla: "Zmiana czasów i zasad naliczania SLA.",
  manage_schedule: "Zmiana godzin pracy, wyjątków kalendarza i odpowiedzi poza biurem.",
  manage_notifications: "Konfiguracja celów i reguł powiadomień.",
  view_global_statistics: "Dostęp do statystyk całej organizacji.",
  reassign_cases: "Administracyjne przepisanie lub odpięcie właściciela sprawy.",
  force_resolve: "Rozwiązanie sprawy poza zwykłą regułą bieżącego właściciela.",
  view_audit: "Przeglądanie przefiltrowanej historii audytowej.",
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
  const t = useT()
  return <span className={`inline-flex items-center gap-1 text-xs ${enabled ? "text-success" : "text-muted-foreground"}`}>{enabled ? <CheckCircle2 className="size-3.5" /> : <Unplug className="size-3.5" />}{enabled ? t("Włączone") : t("Wyłączone")}</span>
}

function SaveButton({ pending, onClick }: { pending: boolean; onClick: () => void }) {
  const t = useT()
  return <div className="flex justify-end border-t pt-4"><Button onClick={onClick} disabled={pending}><Save /> {pending ? t("Zapisywanie…") : t("Zapisz zmiany")}</Button></div>
}

function TimelineItem({ label, value, accent }: { label: string; value: string; accent?: "warning" | "destructive" }) {
  return <div className="flex items-center gap-3"><span className={`size-2.5 rounded-full ${accent === "warning" ? "bg-warning" : accent === "destructive" ? "bg-destructive" : "bg-primary"}`} /><div className="min-w-0 flex-1"><p className="text-xs text-muted-foreground">{label}</p><p className="font-medium">{value}</p></div></div>
}

async function saveSection<K extends keyof AdministrationSettings>(actions: SettingsActions, key: K, value: AdministrationSettings[K], successMessage: string) {
  try {
    await actions.saveSection.mutateAsync({ key, value } as AdministrationSectionInput)
    notify.success(successMessage)
  } catch (error) {
    notify.error(t("Nie udało się zapisać zmian"), getErrorMessage(error))
  }
}

function calculateSlaExample(settings: SlaSettings) {
  const start = new Date(2026, 7, 3, 16, 30)
  const add = (minutes: number) => settings.businessHoursOnly ? addBusinessMinutes(start, minutes) : new Date(start.getTime() + minutes * 60_000)
  const format = (date: Date) => new Intl.DateTimeFormat(locale(), { weekday: "short", hour: "2-digit", minute: "2-digit" }).format(date)
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
  return userError(error)
}
