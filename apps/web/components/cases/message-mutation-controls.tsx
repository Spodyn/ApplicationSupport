"use client"

import { useRef, useState } from "react"
import { Copy, MoreHorizontal, Pencil, Trash2 } from "lucide-react"
import type { InboxMessage } from "@/lib/domain/inbox"
import { useInboxMessageMutations } from "@/lib/services/queries"
import { useT } from "@/lib/i18n/provider"
import { userError } from "@/lib/i18n/errors"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"

export function MessageMutationControls({ message, caseId }: { message: InboxMessage; caseId: string }) {
  const t = useT()
  const actions = useInboxMessageMutations(caseId)
  const [editor, setEditor] = useState<{ body: string; revision: number; operation?: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [copied, setCopied] = useState(false)
  const submission = useRef<{ key: string; body?: string; action: "edit" | "delete" } | null>(null)
  const locked = useRef(false)
  const view = message.mutation
  const active = Boolean(view?.state && ["queued", "running", "retry", "uncertain"].includes(view.state))
  const pending = actions.mutate.isPending || actions.retry.isPending
  if (editor?.operation && view?.operationId === editor.operation && view.state === "confirmed") {
    setEditor(null)
  }
  const reason = (code?: string) => {
    switch (code) {
      case "MULTIPLE_PROVIDER_RESOURCES": return t("Wiadomość zawiera osobne zasoby dostawcy. Pełne usunięcie jest niedostępne.")
      case "NO_TEXT_RESOURCE": return t("Ta wiadomość nie ma edytowalnego zasobu tekstowego.")
      case "PROVIDER_TARGET_UNAVAILABLE": return t("Dostawca nie udostępnił bezpiecznego identyfikatora tej wiadomości.")
      case "PROVIDER_UNSUPPORTED": return t("Ta platforma nie obsługuje zmiany wiadomości.")
      case "INTEGRATION_DISABLED": return t("Integracja jest wyłączona.")
      case "ATTACHMENT_NOT_CLEAN": return t("Załącznik nie jest gotowy do bezpiecznej zmiany wiadomości.")
      case "DELIVERY_NOT_CONFIRMED": return t("Poczekaj na potwierdzenie wysłania wiadomości.")
      case "MUTATION_PENDING": return t("Oczekiwanie na potwierdzenie dostawcy…")
      default: return t("Ta operacja jest niedostępna dla tej wiadomości.")
    }
  }
  const failureReason = (code?: string) => {
    if (code?.includes("PERMISSION") || code?.includes("AUTH") || code?.includes("CREDENTIAL")) return t("Sprawdź uprawnienia i konfigurację integracji.")
    if (code?.includes("NOT_FOUND") || code?.includes("PROVIDER_MESSAGE_DELETED")) return t("Dostawca nie znalazł tej wiadomości.")
    if (code?.includes("RATE_LIMITED") || code?.includes("UNAVAILABLE")) return t("Dostawca jest chwilowo niedostępny. Możesz ponowić bezpieczną próbę.")
    return t("Dostawca nie potwierdził zmiany wiadomości.")
  }
  const send = async (action: "edit" | "delete") => {
    if (!view || locked.current || pending || active || (action === "edit" ? !view.canEdit : !view.canDelete)) return
    locked.current = true
    const body = action === "edit" ? editor?.body : undefined
    if (!submission.current || submission.current.action !== action || submission.current.body !== body) {
      submission.current = { key: crypto.randomUUID(), action, body }
    }
    setError(null)
    try {
      const result = await actions.mutate.mutateAsync({ messageId: message.id, input: {
        action, body, expectedRevision: action === "edit" ? editor!.revision : view.revision, idempotencyKey: submission.current.key,
      } })
      if (action === "edit") setEditor((current) => current ? { ...current, operation: result.operationId } : current)
      setConfirmDelete(false)
      submission.current = null
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("Nie udało się zmienić wiadomości."))) }
    finally { locked.current = false }
  }
  const copy = async () => {
    try { await navigator.clipboard.writeText(message.body); setCopied(true); setError(null) }
    catch { setError(new Error(t("Nie udało się skopiować tekstu. Zaznacz go i skopiuj ręcznie."))) }
  }
  return <>
    <div className="absolute right-1 top-0 z-10 opacity-0 transition-opacity group-hover/message:opacity-100 group-focus-within/message:opacity-100 [@media(hover:none)]:opacity-100 max-md:opacity-100">
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button size="icon-sm" variant="ghost" className="text-[#c9ced8] hover:bg-white/10 hover:text-white" aria-label={t("Akcje wiadomości")} />}><MoreHorizontal /></DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem disabled={!message.body} onClick={() => void copy()}><Copy />{t("Kopiuj tekst")}</DropdownMenuItem>
          {view?.isAuthor && !message.deleted && <>
            <DropdownMenuItem disabled={!view.canEdit || pending} onClick={() => { setEditor({ body: message.body, revision: view.revision }); setError(null); submission.current = null }}><Pencil />{t("Edytuj wiadomość")}</DropdownMenuItem>
            <DropdownMenuItem disabled={!view.canDelete || pending} onClick={() => { setConfirmDelete(true); setError(null); submission.current = null }}><Trash2 />{t("Usuń wiadomość")}</DropdownMenuItem>
          </>}
          {view?.isAuthor && !message.deleted && !active && (!view.canEdit || !view.canDelete) && <div className="max-w-64 space-y-1 px-2 py-1 text-xs text-muted-foreground" data-mutation-unavailable>
            {!view.canEdit && <p>{t("Edytuj wiadomość")}: {reason(view.editUnavailable)}</p>}
            {!view.canDelete && <p>{t("Usuń wiadomość")}: {reason(view.deleteUnavailable)}</p>}
          </div>}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
    {editor && !message.deleted && <div className="mt-2 space-y-2 rounded-md border border-violet-400/30 p-2" data-message-editor>
      <textarea aria-label={t("Nowa treść wiadomości")} value={editor.body} maxLength={40000} disabled={active || pending}
        onChange={(event) => { setEditor({ ...editor, body: event.target.value }); submission.current = null }}
        className="min-h-24 w-full resize-y rounded border border-white/20 bg-[#08111f] p-2 text-sm text-white outline-none focus:border-violet-400" />
      <div className="flex gap-2">
        <Button size="sm" disabled={!editor.body.trim() || active || pending || !view?.canEdit} onClick={() => void send("edit")}>{t("Zapisz zmiany")}</Button>
        <Button size="sm" variant="ghost" className="text-[#c9ced8] hover:bg-white/10 hover:text-white" disabled={active || pending} onClick={() => { setEditor(null); submission.current = null; setError(null) }}>{t("Anuluj")}</Button>
      </div>
    </div>}
    {(active || pending || editor?.operation) && !message.deleted && view?.state !== "failed" && <p role="status" className="mt-1 text-xs text-[#c9ced8]">{view?.state === "uncertain"
      ? t("Wynik u dostawcy jest nieznany. Dalsze zmiany są zablokowane do potwierdzenia.") : t("Oczekiwanie na potwierdzenie dostawcy…")}</p>}
    {view?.state === "failed" && !message.deleted && <div role="alert" className="mt-1 text-xs text-red-300">
      <p>{failureReason(view.errorCode)}</p>
      {view.isAuthor && view.canRetry && view.operationId && <Button size="sm" variant="ghost" className="text-red-200" disabled={pending} onClick={() => {
        setError(null)
        actions.retry.mutate({ messageId: message.id, operationId: view.operationId!, key: crypto.randomUUID() }, {
          onError: (failure) => setError(failure),
          onSuccess: (result) => setEditor((current) => current ? { ...current, operation: result.operationId } : current),
        })
      }}>{t("Ponów zmianę wiadomości")}</Button>}
    </div>}
    {error && <p role="alert" className="mt-1 text-xs text-red-300">{userError(error)}</p>}
    {copied && <span role="status" className="sr-only">{t("Tekst skopiowany")}</span>}
    <Dialog open={confirmDelete} onOpenChange={(open) => { if (!pending) setConfirmDelete(open) }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{t("Usunąć wiadomość?")}</DialogTitle>
          <DialogDescription>{t("Wiadomość zostanie usunięta z rozmowy u dostawcy. Zachowana historia w ApplicationSupport podlega dotychczasowej retencji.")}</DialogDescription>
        </DialogHeader>
        {error && <p role="alert" className="text-sm text-destructive">{userError(error)}</p>}
        <div className="flex justify-end gap-2"><Button variant="outline" disabled={pending} onClick={() => setConfirmDelete(false)}>{t("Anuluj")}</Button>
          <Button variant="destructive" disabled={pending || active || !view?.canDelete} onClick={() => void send("delete")}>{t("Usuń wiadomość")}</Button></div>
      </DialogContent>
    </Dialog>
  </>
}
