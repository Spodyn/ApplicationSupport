"use client"

import { useT } from "@/lib/i18n/provider"
import { useRef, useState, type ReactNode } from "react"
import { notify } from "@/components/design-system/notify"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { useBulkInboxCases, useCurrentUser, useInboxAssignees } from "@/lib/services/queries"
import type { InboxBulkInput, InboxBulkResult } from "@/lib/services/inbox"

type PendingBulk = InboxBulkInput & { expiresAt: number }
const actions = ["ASSIGN", "RESOLVE", "IGNORE", "CLAIM"] as const

function recoverBulk(storageKey: string): PendingBulk | null {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (!raw) return null
    const command = JSON.parse(raw) as PendingBulk
    if (command.expiresAt > Date.now() && actions.includes(command.action)
        && Array.isArray(command.caseIds) && command.caseIds.length > 0
        && typeof command.idempotencyKey === "string") return command
    sessionStorage.removeItem(storageKey)
  } catch {
    // Storage can be disabled by the browser.
  }
  return null
}

function rememberBulk(storageKey: string, operation: PendingBulk | null) {
  try {
    if (operation) sessionStorage.setItem(storageKey, JSON.stringify(operation))
    else sessionStorage.removeItem(storageKey)
  } catch {
    // The mounted dialog still retains the frozen command in memory.
  }
}

export function BulkCaseToolbar({ active, selected, references, clear, children }: {
  active: boolean
  selected: string[]
  references: ReadonlyMap<string, string>
  clear: () => void
  children: ReactNode
}) {
  const t = useT()
  const mutation = useBulkInboxCases()
  const user = useCurrentUser()
  const storageKey = "usi-bulk:" + user.data?.id
  const [pending, setPending] = useState(() => recoverBulk(storageKey))
  const [action, setAction] = useState<InboxBulkInput["action"] | null>(pending?.action ?? null)
  const [target, setTarget] = useState(pending?.targetUserId ?? "")
  const [snapshot, setSnapshot] = useState<string[]>(pending?.caseIds ?? [])
  const [results, setResults] = useState<InboxBulkResult[]>([])
  const assignees = useInboxAssignees(snapshot[0], action === "ASSIGN")
  const locked = useRef(false)
  const [error, setError] = useState(Boolean(pending))
  const labels = { ASSIGN: t("Przypisz"), RESOLVE: t("Rozwiąż"), IGNORE: t("Ignoruj"), CLAIM: t("Przejmij") }

  const open = (next: InboxBulkInput["action"]) => {
    setSnapshot([...selected])
    setTarget("")
    setError(false)
    setAction(next)
  }
  const submit = async () => {
    if (!action || locked.current) return
    if (pending && pending.expiresAt <= Date.now()) {
      rememberBulk(storageKey, null)
      setPending(null)
      setAction(null)
      notify.warning(t("Wygasł czas na odzyskanie wyniku operacji zbiorczej."))
      return
    }
    locked.current = true
    setError(false)
    const operation = pending ?? {
      caseIds: snapshot,
      action,
      targetUserId: target || undefined,
      idempotencyKey: crypto.randomUUID(),
      expiresAt: Date.now() + 24 * 60 * 60 * 1000,
    }
    setPending(operation)
    rememberBulk(storageKey, operation)
    try {
      const { caseIds, action, targetUserId, idempotencyKey } = operation
      const outcome = await mutation.mutateAsync({ caseIds, action, targetUserId, idempotencyKey })
      rememberBulk(storageKey, null)
      setPending(null)
      setResults(outcome)
      setAction(null)
      clear()
    } catch { setError(true) }
    finally { locked.current = false }
  }
  return <>
    {pending && !action && (
      <Button size="sm" variant="outline" className="mx-4 mb-2 self-start" onClick={() => setAction(pending.action)}>
        {t("Odzyskaj wynik operacji zbiorczej")}
      </Button>
    )}
    {active && <div role="group" aria-label={t("Operacje zbiorcze")} className="mx-4 mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-violet-500/30 bg-[#151d32] p-3">
      <span className="w-full text-xs" aria-live="polite">{t("Zaznaczono:")} {selected.length}</span>
      {children}
      {actions.filter((value) => value !== "ASSIGN" || (user.data?.role === "admin" && user.data.effectivePermissions.includes("reassign_cases"))).map((value) => <Button key={value} size="sm" variant="outline" onClick={() => open(value)} disabled={Boolean(pending) || selected.length === 0}>{labels[value]}</Button>)}
      <Button size="sm" variant="ghost" onClick={clear} disabled={mutation.isPending || selected.length === 0}>{t("Wyczyść zaznaczenie")}</Button>
    </div>}
    {results.length > 0 && <details className="mx-4 mb-3 rounded-lg border border-white/10 p-2 text-xs" open>
      <summary role="status">{t("Wynik: wykonano: {{succeeded}}; pominięto: {{skipped}}; błędy: {{failed}}", { succeeded: results.filter((item) => item.status === "SUCCEEDED").length, skipped: results.filter((item) => item.status === "SKIPPED").length, failed: results.filter((item) => item.status === "FAILED").length })}</summary>
      <p className="mt-2">{t("Zaznaczone sprawy:")} {results.length}</p>
      <ul className="mt-2 max-h-32 overflow-auto">{results.map((item) => <li key={item.caseId} className="break-all">{references.get(item.caseId) ?? item.caseId}: {t(item.status)}{item.status !== "SUCCEEDED" && <> · {t(bulkReason(item.code))} ({item.code})</>}</li>)}</ul>
    </details>}
    <Dialog open={Boolean(action)} onOpenChange={(open) => { if (!open && !mutation.isPending) setAction(null) }}>
      <DialogContent showCloseButton={!mutation.isPending}>
        <DialogHeader><DialogTitle>{action ? labels[action] : t("Operacje zbiorcze")}</DialogTitle>
          <DialogDescription>{t("Zaznaczone sprawy:")} {snapshot.length}{t(". Każda sprawa zostanie sprawdzona oddzielnie. Niedostępne akcje będą pominięte.")}</DialogDescription>
        </DialogHeader>
        {action === "IGNORE" && <p className="text-sm">{t("To oddaje Twój głos ignorowania. Po oddaniu głosu nie możesz przejąć tej sprawy ani na nią odpowiedzieć.")}</p>}
        {action === "RESOLVE" && <p className="text-sm">{t("Rozwiązane sprawy są zakończone i nie można ich ponownie otworzyć. Rozwiązanie dotyczy tylko spraw, których jesteś właścicielem.")}</p>}
        {action === "ASSIGN" && <label className="grid gap-2 text-sm">{t("Agent")}<select aria-label={t("Agent")} value={target} disabled={Boolean(pending)} onChange={(event) => setTarget(event.target.value)} className="rounded-md border bg-background p-2"><option value="">{t("Wybierz agenta")}</option>{assignees.data?.filter((agent) => agent.eligible).map((agent) => <option key={agent.id} value={agent.id}>{agent.displayName}</option>)}</select></label>}
        {assignees.isError && action === "ASSIGN" && <p role="alert">{t("Nie udało się wczytać agentów.")}</p>}
        {error && <p role="alert">{t("Nie otrzymano pełnego wyniku. Ponów tę samą operację, aby bezpiecznie odzyskać wyniki.")}</p>}
        <div className="flex justify-end gap-2"><Button variant="outline" disabled={mutation.isPending} onClick={() => setAction(null)}>{t(pending ? "Zamknij" : "Anuluj")}</Button><Button disabled={mutation.isPending || (action === "ASSIGN" && !target)} onClick={() => void submit()}>{mutation.isPending ? t("Zapisywanie…") : error ? t("Ponów") : t("Zatwierdź")}</Button></div>
      </DialogContent>
    </Dialog>
  </>
}

function bulkReason(code: string) {
  switch (code) {
    case "ACTION_UNAVAILABLE": return "Akcja jest niedostępna dla tej sprawy."
    case "RESOURCE_NOT_FOUND": return "Nie znaleziono zasobu."
    case "ACCESS_DENIED": return "Nie masz uprawnień do wykonania tej akcji."
    case "CASE_ALREADY_CLAIMED": return "Case został już przejęty przez innego agenta."
    case "CONFLICT": return "Dane zmieniły się. Odśwież widok i spróbuj ponownie."
    default: return "Nie udało się wykonać akcji."
  }
}
