"use client"

import { useT } from "@/lib/i18n/provider"
import { useRef, useState } from "react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { useBulkInboxCases, useCurrentUser, useInboxAssignees } from "@/lib/services/queries"
import type { InboxBulkInput, InboxBulkResult } from "@/lib/services/inbox"

export function BulkCaseToolbar({ selected, clear }: { selected: string[]; clear: () => void }) {
  const t = useT()
  const mutation = useBulkInboxCases()
  const user = useCurrentUser()
  const [action, setAction] = useState<InboxBulkInput["action"] | null>(null)
  const [target, setTarget] = useState("")
  const [snapshot, setSnapshot] = useState<string[]>([])
  const [results, setResults] = useState<InboxBulkResult[]>([])
  const assignees = useInboxAssignees(snapshot[0], action === "ASSIGN")
  const key = useRef<string | null>(null)
  const locked = useRef(false)
  const [error, setError] = useState(false)
  const labels = { ASSIGN: t("Przypisz"), RESOLVE: t("Rozwiąż"), IGNORE: t("Ignoruj"), CLAIM: t("Przejmij") }

  const open = (next: InboxBulkInput["action"]) => {
    key.current = crypto.randomUUID()
    setSnapshot([...selected])
    setTarget("")
    setError(false)
    setAction(next)
  }
  const submit = async () => {
    if (!action || !key.current || locked.current) return
    locked.current = true
    setError(false)
    try {
      const outcome = await mutation.mutateAsync({ caseIds: snapshot, action, targetUserId: target || undefined, idempotencyKey: key.current })
      setResults(outcome)
      setAction(null)
      clear()
    } catch { setError(true) }
    finally { locked.current = false }
  }
  return <>
    {selected.length > 0 && <div aria-label={t("Operacje zbiorcze")} className="mx-4 mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-violet-500/30 bg-[#151d32] p-3">
      <span className="w-full text-xs" aria-live="polite">{t("Zaznaczono:")} {selected.length}</span>
      {(["ASSIGN", "RESOLVE", "IGNORE", "CLAIM"] as const).filter((value) => value !== "ASSIGN" || (user.data?.role === "admin" && user.data.effectivePermissions.includes("reassign_cases"))).map((value) => <Button key={value} size="sm" variant="outline" onClick={() => open(value)} disabled={mutation.isPending}>{labels[value]}</Button>)}
      <Button size="sm" variant="ghost" onClick={clear} disabled={mutation.isPending}>{t("Wyczyść zaznaczenie")}</Button>
    </div>}
    {results.length > 0 && <details className="mx-4 mb-3 rounded-lg border border-white/10 p-2 text-xs" open>
      <summary role="status">{t("Wynik: wykonano: {{succeeded}}; pominięto: {{skipped}}; błędy: {{failed}}", { succeeded: results.filter((item) => item.status === "SUCCEEDED").length, skipped: results.filter((item) => item.status === "SKIPPED").length, failed: results.filter((item) => item.status === "FAILED").length })}</summary>
      <ul className="mt-2 max-h-32 overflow-auto">{results.map((item) => <li key={item.caseId} className="break-all">{item.caseId}: {t(item.status)} · {item.code}</li>)}</ul>
    </details>}
    <Dialog open={Boolean(action)} onOpenChange={(open) => { if (!open && !mutation.isPending) setAction(null) }}>
      <DialogContent showCloseButton={!mutation.isPending}>
        <DialogHeader><DialogTitle>{action ? labels[action] : t("Operacje zbiorcze")}</DialogTitle>
          <DialogDescription>{t("Zaznaczone sprawy:")} {snapshot.length}{t(". Każda sprawa zostanie sprawdzona oddzielnie. Niedostępne akcje będą pominięte.")}</DialogDescription>
        </DialogHeader>
        {action === "IGNORE" && <p className="text-sm">{t("To oddaje Twój głos ignorowania. Po oddaniu głosu nie możesz przejąć tej sprawy ani na nią odpowiedzieć.")}</p>}
        {action === "RESOLVE" && <p className="text-sm">{t("Rozwiązane sprawy są zakończone i nie można ich ponownie otworzyć. Rozwiązanie dotyczy tylko spraw, których jesteś właścicielem.")}</p>}
        {action === "ASSIGN" && <label className="grid gap-2 text-sm">{t("Agent")}<select aria-label={t("Agent")} value={target} disabled={mutation.isPending || error} onChange={(event) => { setTarget(event.target.value); key.current = crypto.randomUUID() }} className="rounded-md border bg-background p-2"><option value="">{t("Wybierz agenta")}</option>{assignees.data?.map((agent) => <option key={agent.id} value={agent.id}>{agent.displayName}</option>)}</select></label>}
        {assignees.isError && action === "ASSIGN" && <p role="alert">{t("Nie udało się wczytać agentów.")}</p>}
        {error && <p role="alert">{t("Nie otrzymano pełnego wyniku. Ponów tę samą operację, aby bezpiecznie odzyskać wyniki.")}</p>}
        <div className="flex justify-end gap-2"><Button variant="outline" disabled={mutation.isPending} onClick={() => setAction(null)}>{t("Anuluj")}</Button><Button disabled={mutation.isPending || (action === "ASSIGN" && !target)} onClick={() => void submit()}>{mutation.isPending ? t("Zapisywanie…") : error ? t("Ponów") : t("Zatwierdź")}</Button></div>
      </DialogContent>
    </Dialog>
  </>
}
