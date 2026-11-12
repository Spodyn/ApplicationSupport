"use client"

import { ArrowLeft, MessageCircle, Search } from "lucide-react"
import { useEffect, useRef, useState } from "react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { PageHeader } from "@/components/layout/page-header"
import { formatDateTime } from "@/lib/format"
import { userError } from "@/lib/i18n/errors"
import { useT } from "@/lib/i18n/provider"
import type { DirectConversation } from "@/lib/services/direct-messages"
import {
  useCurrentUser, useDirectConversation, useDirectConversations, useDirectHistory,
  useDirectMessageActions, useDirectRecipients,
} from "@/lib/services/queries"
import { cn } from "@/lib/utils"

function Initials({ name }: { name: string }) {
  return (
    <span aria-hidden="true" className="grid size-9 shrink-0 place-items-center rounded-full bg-violet-500/20 text-xs font-semibold text-violet-300">
      {name.trim().split(/\s+/).slice(0, 2).map((part) => Array.from(part)[0]).join("").toUpperCase()}
    </span>
  )
}

function useSearch(value: string) {
  const [search, setSearch] = useState("")
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(value.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [value])
  return search
}

export function DirectMessagesPage({ initialConversation }: { initialConversation: string }) {
  const { data: user } = useCurrentUser()
  if (!user) return null
  return <Workspace key={user.id} accountId={user.id} initialConversation={initialConversation} />
}

function Workspace({ accountId, initialConversation }: { accountId: string; initialConversation: string }) {
  const t = useT()
  const [selected, setSelected] = useState(initialConversation)
  const [searchInput, setSearchInput] = useState("")
  const [newMessage, setNewMessage] = useState(false)
  const conversations = useDirectConversations(accountId, useSearch(searchInput))
  const conversation = useDirectConversation(accountId, selected)
  const rows = new Map(conversations.data?.pages.flatMap((page) => page.items.map((item) => [item.id, item] as const)))

  function select(id: string) {
    setSelected(id)
    const url = new URL(window.location.href)
    if (id) url.searchParams.set("conversation", id)
    else url.searchParams.delete("conversation")
    window.history.replaceState(null, "", url)
  }

  return (
    <>
      <PageHeader title={t("Wiadomości prywatne")} actions={
        <Button size="sm" variant="outline" onClick={() => setNewMessage(true)}>
          <MessageCircle />{t("Nowa wiadomość")}
        </Button>
      } />
      <main className="grid min-h-0 min-w-0 flex-1 grid-cols-1 overflow-hidden bg-[#08111f] text-[#edf0f4] lg:grid-cols-[minmax(17rem,23rem)_minmax(0,1fr)]">
        <section aria-label={t("Rozmowy prywatne")} className={cn(
          "min-h-0 min-w-0 flex-col border-r border-white/10 bg-[#0b1422]",
          selected ? "hidden lg:flex" : "flex",
        )}>
          <div className="relative m-4 shrink-0">
            <Search aria-hidden="true" className="absolute left-3 top-2.5 size-4 text-[#9ba6b6]" />
            <Input aria-label={t("Szukaj rozmów")} placeholder={t("Szukaj rozmów...")} maxLength={100}
              value={searchInput} onChange={(event) => setSearchInput(event.target.value)}
              className="border-white/10 bg-[#111b29] pl-9 text-[#edf0f4] placeholder:text-[#9ba6b6]" />
          </div>
          <div className="cases-scrollbar min-h-0 flex-1 space-y-1 overflow-y-auto px-3 pb-3">
            {conversations.isPending && <p className="px-2 text-sm text-[#aeb8c8]">{t("Wczytywanie…")}</p>}
            {conversations.isError && <p className="px-2 text-sm text-red-200" role="alert">{userError(conversations.error)}</p>}
            {conversations.isSuccess && rows.size === 0 && <p className="px-2 text-sm text-[#aeb8c8]">{t("Brak rozmów prywatnych.")}</p>}
            {[...rows.values()].map((item) => (
              <Button key={item.id} variant="ghost" aria-pressed={selected === item.id} onClick={() => select(item.id)}
                className="h-auto w-full justify-start gap-3 rounded-lg border border-transparent px-3 py-3 text-left text-[#edf0f4] hover:bg-white/5 aria-pressed:border-violet-500/30 aria-pressed:bg-violet-500/15">
                <Initials name={item.recipient.displayName} />
                <span className="min-w-0 flex-1 truncate">{item.recipient.displayName}</span>
                {item.unreadCount > 0 && (
                  <span className="rounded-full bg-violet-600 px-2 text-xs text-white" aria-label={t("Nieprzeczytane: {{count}}", { count: item.unreadCount })}>
                    {item.unreadCount}
                  </span>
                )}
              </Button>
            ))}
            {conversations.hasNextPage && (
              <Button size="sm" variant="ghost" disabled={conversations.isFetchingNextPage} onClick={() => void conversations.fetchNextPage()}>
                {t("Wczytaj więcej")}
              </Button>
            )}
          </div>
        </section>
        {selected && conversation.data ? (
          <ConversationPane key={selected} accountId={accountId} conversation={conversation.data} back={() => select("")} />
        ) : (
          <div className={cn("min-w-0 place-content-center gap-3 p-4 text-center text-sm text-[#aeb8c8]",
            selected ? "grid" : "hidden lg:grid")}>
            {selected && <Button variant="outline" className="mx-auto text-foreground lg:hidden" onClick={() => select("")}><ArrowLeft />{t("Wróć do listy")}</Button>}
            {selected && conversation.isError ? <p role="alert">{userError(conversation.error)}</p>
              : <p>{selected ? t("Wczytywanie…") : t("Wybierz lub rozpocznij rozmowę prywatną.")}</p>}
          </div>
        )}
      </main>
      {newMessage && <RecipientDialog accountId={accountId} onClose={() => setNewMessage(false)}
        onStart={(id) => { select(id); setNewMessage(false) }} />}
    </>
  )
}

function RecipientDialog({ accountId, onClose, onStart }: { accountId: string; onClose: () => void; onStart: (id: string) => void }) {
  const t = useT()
  const [input, setInput] = useState("")
  const recipients = useDirectRecipients(accountId, useSearch(input), true)
  const { start } = useDirectMessageActions(accountId)
  const rows = recipients.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <Dialog open onOpenChange={(open) => { if (!open && !start.isPending) onClose() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("Nowa wiadomość")}</DialogTitle>
          <DialogDescription>{t("Wybierz osobę, z którą chcesz porozmawiać.")}</DialogDescription>
        </DialogHeader>
        <Input aria-label={t("Szukaj osób")} placeholder={t("Szukaj osób")} maxLength={100}
          value={input} onChange={(event) => setInput(event.target.value)} />
        <div className="max-h-[40dvh] overflow-y-auto">
          {recipients.isPending && <p className="text-sm text-muted-foreground">{t("Wczytywanie…")}</p>}
          {recipients.isSuccess && !rows.length && <p className="text-sm text-muted-foreground">{t("Nie znaleziono osób.")}</p>}
          {(recipients.isError || start.isError) && <p role="alert" className="text-sm text-destructive">{userError(start.error ?? recipients.error)}</p>}
          {rows.map((recipient) => (
            <Button key={recipient.id} variant="ghost" disabled={start.isPending} className="h-auto w-full justify-start gap-3 px-2 py-2"
              onClick={() => start.mutate(recipient.id, { onSuccess: (conversation) => onStart(conversation.id) })}>
              <Initials name={recipient.displayName} /><span className="min-w-0 truncate">{recipient.displayName}</span>
            </Button>
          ))}
          {recipients.hasNextPage && (
            <Button size="sm" variant="outline" disabled={recipients.isFetchingNextPage} onClick={() => void recipients.fetchNextPage()}>{t("Wczytaj więcej")}</Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function ConversationPane({ accountId, conversation, back }: { accountId: string; conversation: DirectConversation; back: () => void }) {
  const t = useT()
  const { id, recipient, recipientActive } = conversation
  const history = useDirectHistory(accountId, id)
  const { send, markRead } = useDirectMessageActions(accountId)
  const [draft, setDraft] = useState("")
  const sending = useRef(false)
  const composing = useRef(false)
  const key = useRef<string | null>(null)
  const seen = useRef<string | null>(null)
  const scroll = useRef<HTMLDivElement>(null)
  const newest = history.data?.pages[0]?.items[0]?.id
  const rows = new Map(history.data?.pages.flatMap((page) => page.items.map((item) => [item.id, item] as const)))
  const markReadMutation = markRead.mutate

  useEffect(() => {
    function readVisible() {
      if (!newest || document.hidden || seen.current === newest) return
      seen.current = newest
      markReadMutation({ id, messageId: newest }, { onError: () => { seen.current = null } })
    }
    readVisible()
    document.addEventListener("visibilitychange", readVisible)
    return () => document.removeEventListener("visibilitychange", readVisible)
  }, [id, newest, markReadMutation, history.dataUpdatedAt])

  useEffect(() => {
    if (scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight
  }, [newest])

  async function submit() {
    if (!recipientActive || !draft.trim() || sending.current) return
    sending.current = true
    key.current ??= crypto.randomUUID()
    try {
      await send.mutateAsync({ conversationId: id, body: draft, idempotencyKey: key.current })
      setDraft("")
      key.current = null
    } catch {
      // The draft and key remain available for a safe retry.
    } finally {
      sending.current = false
    }
  }

  return (
    <section aria-label={t("Rozmowa {{value0}}", { value0: recipient.displayName })} className="flex min-h-0 min-w-0 flex-col">
      <header className="flex min-w-0 shrink-0 items-center gap-3 border-b border-white/10 px-4 py-3">
        <Button aria-label={t("Wróć do listy")} variant="ghost" size="icon-sm" className="lg:hidden" onClick={back}><ArrowLeft /></Button>
        <Initials name={recipient.displayName} />
        <h2 className="min-w-0 truncate font-semibold">{recipient.displayName}</h2>
      </header>
      <div ref={scroll} className="cases-scrollbar min-h-0 min-w-0 flex-1 space-y-3 overflow-y-auto px-4 py-4 sm:px-6">
        {history.isPending && <p className="text-sm text-[#aeb8c8]">{t("Wczytywanie wiadomości…")}</p>}
        {history.isError && <p className="text-sm text-red-200" role="alert">{userError(history.error)}</p>}
        {history.isSuccess && rows.size === 0 && <p className="text-sm text-[#aeb8c8]">{t("Brak wiadomości.")}</p>}
        {history.hasNextPage && (
          <Button size="sm" variant="ghost" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>
            {t("Wczytaj starsze wiadomości")}
          </Button>
        )}
        {[...rows.values()].reverse().map((message) => (
          <div key={message.id} className={cn("flex min-w-0", message.senderId === accountId && "justify-end")}>
            <div className={cn("min-w-0 max-w-[min(36rem,100%)] rounded-xl px-4 py-2 [overflow-wrap:anywhere]",
              message.senderId === accountId ? "bg-violet-950/70" : "bg-[#142032]")}>
              <p className="whitespace-pre-wrap text-sm">{message.body}</p>
              <time dateTime={message.createdAt} className="text-xs text-[#bdc7d7]">{formatDateTime(message.createdAt)}</time>
            </div>
          </div>
        ))}
      </div>
      <form onSubmit={(event) => { event.preventDefault(); void submit() }} className="grid min-w-0 shrink-0 gap-2 border-t border-white/10 px-4 py-3">
        {!recipientActive && <p className="text-sm text-[#aeb8c8]">{t("Ten użytkownik jest obecnie niedostępny.")}</p>}
        {send.isError && <p className="text-sm text-red-200" role="alert">{userError(send.error)}</p>}
        <div className="flex min-w-0 items-end gap-2">
          <textarea aria-label={t("Treść wiadomości prywatnej")} placeholder={t("Napisz wiadomość…")} value={draft}
            maxLength={4000} rows={2} readOnly={send.isPending} disabled={!recipientActive}
            className="min-w-0 flex-1 resize-none rounded-lg border border-white/15 bg-[#111b29] px-3 py-2 text-sm text-[#edf0f4] placeholder:text-[#aeb8c8] disabled:opacity-50"
            onChange={(event) => { if (!sending.current) { setDraft(event.target.value); key.current = null } }}
            onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
            onKeyDown={(event) => {
              if (event.key !== "Enter" || event.shiftKey || composing.current || event.nativeEvent.isComposing || event.keyCode === 229) return
              event.preventDefault()
              if (!event.repeat) void submit()
            }} />
          <Button type="submit" disabled={!recipientActive || send.isPending || !draft.trim()}>
            {send.isPending ? t("Wysyłanie…") : t("Wyślij")}
          </Button>
        </div>
      </form>
    </section>
  )
}
