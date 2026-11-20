"use client"

import { useState } from "react"
import { useTranslation } from "react-i18next"
import type { IntegrationResyncSettings } from "@/lib/domain/administration"
import { useIntegrationResync, useIntegrationResyncActions } from "@/lib/services/queries"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Label } from "@/components/ui/label"

export function IntegrationResyncPanel({ id, disabled }: { id: string; disabled: boolean }) {
  const { t } = useTranslation()
  const query = useIntegrationResync(id)
  const actions = useIntegrationResyncActions(id)
  const [draft, setDraft] = useState<IntegrationResyncSettings>()
  const [mode, setMode] = useState<"from_date" | "all_history">("from_date")
  const [from, setFrom] = useState("")
  const [error, setError] = useState("")
  const settings = draft ?? query.data?.settings
  if (!settings) return <p role={query.isError ? "alert" : "status"} className="text-xs text-muted-foreground">{query.isError ? t("Nie udało się pobrać ustawień resyncu.") : t("Ładowanie ustawień resyncu…")}</p>
  const update = (change: Partial<IntegrationResyncSettings>) => setDraft({ ...settings, ...change })
  const busy = actions.save.isPending || actions.start.isPending
  return <section className="grid gap-3 border-t pt-3" aria-label={t("Odzyskiwanie wiadomości (resync)")}>
    <p className="text-sm font-medium">{t("Odzyskiwanie wiadomości (resync)")}</p>
    <div className="flex items-center justify-between gap-2"><Label htmlFor={`${id}-resync`}>{t("Włącz resync")}</Label><Switch id={`${id}-resync`} checked={settings.enabled} disabled={busy} onCheckedChange={(enabled) => update({ enabled, automatic: enabled && settings.automatic })} /></div>
    <div className="flex items-center justify-between gap-2"><Label htmlFor={`${id}-automatic`}>{t("Automatyczny resync")}</Label><Switch id={`${id}-automatic`} checked={settings.automatic} disabled={!settings.enabled || busy} onCheckedChange={(automatic) => update({ automatic })} /></div>
    <div className="grid grid-cols-2 gap-2">
      <Label className="grid gap-1">{t("Interwał (minuty)")}<Input type="number" min={1} max={10080} value={settings.intervalMinutes} onChange={(e) => update({ intervalMinutes: Number(e.target.value) })} /></Label>
      <Label className="grid gap-1">{t("Zakres historii (minuty)")}<Input type="number" min={1} max={5256000} value={settings.lookbackMinutes} onChange={(e) => update({ lookbackMinutes: Number(e.target.value) })} /></Label>
    </div>
    <Button variant="outline" size="sm" disabled={busy || !draft} onClick={async () => {
      try { setError(""); await actions.save.mutateAsync(settings); setDraft(undefined) } catch (e) { setError(e instanceof Error ? e.message : t("Nie udało się zapisać ustawień resyncu.")) }
    }}>{t("Zapisz ustawienia resyncu")}</Button>
    <fieldset className="grid gap-2" disabled={disabled || !query.data?.settings.enabled || busy || Boolean(draft)}>
      <legend className="mb-2 text-xs font-medium">{t("Resync ręczny")}</legend>
      <Label className="flex items-center gap-2"><input type="radio" name={`${id}-mode`} checked={mode === "from_date"} onChange={() => setMode("from_date")} />{t("Od daty")}</Label>
      {mode === "from_date" && <Input aria-label={t("Od daty")} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />}
      <Label className="flex items-center gap-2"><input type="radio" name={`${id}-mode`} checked={mode === "all_history"} onChange={() => setMode("all_history")} />{t("Od początku dostępnej historii")}</Label>
      <Button size="sm" disabled={mode === "from_date" && !from} onClick={async () => {
        try { setError(""); await actions.start.mutateAsync({ mode, from: mode === "from_date" ? new Date(from).toISOString() : undefined }) } catch (e) { setError(e instanceof Error ? e.message : t("Nie udało się uruchomić resyncu.")) }
      }}>{t("Uruchom resync")}</Button>
    </fieldset>
    {query.isError && <p role="alert" className="text-xs text-destructive">{t("Nie udało się pobrać ustawień resyncu.")}</p>}
    {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
    <p className="text-xs text-muted-foreground">{t("Ostatnie uruchomienie")}: {query.data?.settings.lastRunAt ? new Date(query.data.settings.lastRunAt).toLocaleString() : "—"}<br />{t("Następne uruchomienie")}: {query.data?.settings.nextRunAt ? new Date(query.data.settings.nextRunAt).toLocaleString() : "—"}</p>
    <div aria-live="polite" className="grid gap-3 text-xs">
      {query.data?.runs.map((run) => <div key={run.id} className="grid gap-1 rounded-md border p-2">
        <p className="font-medium">{run.source === "manual" ? t("Resync ręczny") : t("Automatyczny resync")} · {new Date(run.createdAt).toLocaleString()}</p>
        {run.channels.length === 0 && <p>{t("Brak aktywnych monitorowanych kanałów.")}</p>}
        {run.channels.map((channel) => <div key={channel.channelId}>
          <p>{channel.channelName} · {t(`resync_status_${channel.status}`)}</p>
          <p>{t("Dodano")}: {channel.added} · {t("Przeskanowano")}: {channel.scanned} · {t("Oczekuje na zapis")}: {channel.pending}</p>
          {channel.errorCode && <p className="text-orange-500">{t("Synchronizacja niepełna")}: {t(recoveryError(channel.errorCode))} <span className="font-mono">({channel.errorCode})</span></p>}
        </div>)}
      </div>)}
    </div>
  </section>
}

function recoveryError(code: string) {
  if (/HTTP_403|MISSING_SCOPE|NOT_IN_CHANNEL|CHANNEL_NOT_FOUND/.test(code)) return "Brak uprawnień do odczytu kanału. Sprawdź uprawnienia aplikacji i dostęp do kanału."
  if (/TEAM_CONTEXT_MISSING|BOT_ROUTE_MISSING/.test(code)) return "Brak konfiguracji kanału Teams. Sprawdź instalację aplikacji i odbiór zdarzeń."
  if (/RATE_LIMIT|HTTP_429/.test(code)) return "Osiągnięto limit API. Zadanie zostanie automatycznie ponowione."
  if (/HTTP_401|INVALID_AUTH|TOKEN_MISSING|AUTH_FAILED/.test(code)) return "Nie można uwierzytelnić integracji. Sprawdź jej poświadczenia."
  if (/UNAVAILABLE|HTTP_5|INTERRUPTED/.test(code)) return "API jest chwilowo niedostępne. Zadanie zostanie automatycznie ponowione."
  return "Sprawdź konfigurację integracji i szczegóły błędu."
}
