"use client"

import { useT } from "@/lib/i18n/provider"
import type { ManagedIntegration, ProviderRuntimeSetup } from "@/lib/domain/administration"
import { formatDateTime } from "@/lib/format"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { notify } from "@/components/design-system/notify"

export function CallbackAddress({ value }: { value?: string }) {
  const t = useT()
  if (!value) return null
  return <div className="grid gap-1"><Label>{t("Adres odbioru")}</Label><div className="flex gap-2">
    <Input readOnly aria-label={t("Adres odbioru")} value={value} className="min-w-0 text-xs" />
    <Button variant="outline" size="sm" onClick={() => { void navigator.clipboard.writeText(value).then(() => notify.success(t("Skopiowano adres odbioru"))).catch(() => notify.error(t("Nie udało się skopiować adresu."))) }}>{t("Kopiuj")}</Button>
  </div></div>
}

const states: Record<string, string> = {
  MISSING_BOT_CONFIGURATION: "Brakuje konfiguracji bota na VPS",
  TEAM_REQUIRED: "Wybierz zespół Teams",
  DEFAULT_CUSTOMER_REQUIRED: "Wybierz domyślnego klienta",
  INSTALLATION_REQUIRED: "Potrzebna instalacja lub zgoda w zespole",
  DISCOVERING: "Trwa wykrywanie kanałów",
  READY: "Integracja jest gotowa",
  ERROR: "Wykrywanie kanałów wymaga uwagi",
  IDLE: "Oczekuje na wykrycie kanałów",
}

export function ProviderConnectionStatus({ integration, runtime, pending, setAutomatic }: {
  integration: ManagedIntegration; runtime?: ProviderRuntimeSetup; pending: boolean; setAutomatic: (automatic: boolean) => void
}) {
  const t = useT()
  return <section className="grid gap-3 border-t pt-3" aria-label={t("Konfiguracja połączenia")}>
    <CallbackAddress value={integration.callbackUrl ?? runtime?.callbackUrl} />
    <p role="status" className="text-xs font-medium">{t(states[integration.connectionState ?? "IDLE"] ?? states.ERROR)}</p>
    {integration.discoveryErrorCode && <p className="break-all text-xs text-orange-500">{integration.discoveryErrorCode}</p>}
    <div className="flex items-center justify-between gap-2"><Label htmlFor={`${integration.id}-discovery`}>{t("Automatycznie wykrywaj kanały")}</Label><Switch id={`${integration.id}-discovery`} checked={integration.automaticDiscovery ?? false} disabled={pending} onCheckedChange={setAutomatic} /></div>
    {integration.platform === "slack" ? <p className="text-xs text-muted-foreground">{t("Zaproś bota do kanału w Slacku")}</p> : <>
      <p className="text-xs text-muted-foreground">{t("Zainstaluj lub zaktualizuj aplikację w wybranym zespole i zaakceptuj odczyt wiadomości oraz listy kanałów.")}</p>
      {runtime?.packageUrl && <Button variant="outline" size="sm" render={<a href={runtime.packageUrl} download />}>{t("Pobierz paczkę Teams")}</Button>}
    </>}
    {integration.lastDiscoveredAt && <p className="text-xs text-muted-foreground">{t("Ostatnie wykrywanie")}: {formatDateTime(integration.lastDiscoveredAt)} · {t("Kanały")}: {integration.discoveredChannels ?? 0}</p>}
  </section>
}
