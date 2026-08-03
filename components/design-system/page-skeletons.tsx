import { Skeleton } from "@/components/ui/skeleton"

function Lines({ widths = ["w-2/3", "w-full"] }: { widths?: string[] }) {
  return <div className="grid gap-2">{widths.map((width, index) => <Skeleton key={index} className={`h-3 ${width}`} />)}</div>
}

export function InboxPageSkeleton() {
  return (
    <div className="grid min-h-0 flex-1 grid-cols-1 xl:grid-cols-[220px_390px_minmax(0,1fr)]" aria-label="Wczytywanie skrzynki" role="status">
      <div className="hidden border-r bg-card p-3 xl:grid xl:content-start xl:gap-2">{Array.from({ length: 8 }).map((_, index) => <Skeleton key={index} className="h-9 w-full" />)}</div>
      <div className="grid min-h-0 content-start gap-0 overflow-hidden border-r bg-card">{Array.from({ length: 7 }).map((_, index) => <div key={index} className="grid gap-3 border-b p-3"><div className="flex gap-3"><Skeleton className="size-8 rounded-md" /><div className="min-w-0 flex-1"><Lines widths={["w-2/5", "w-3/5"]} /></div></div><Lines widths={["w-4/5", "w-full"]} /><div className="flex gap-2"><Skeleton className="h-5 w-24 rounded-full" /><Skeleton className="h-5 w-20 rounded-full" /></div></div>)}</div>
      <ConversationSkeleton className="hidden xl:flex" />
    </div>
  )
}

export function ConversationSkeleton({ className = "flex" }: { className?: string }) {
  return (
    <div className={`${className} min-h-0 flex-col bg-background`} aria-label="Wczytywanie rozmowy" role="status">
      <div className="flex items-center gap-3 border-b bg-card p-4"><Skeleton className="size-9 rounded-md" /><div className="flex-1"><Lines widths={["w-1/3", "w-1/2"]} /></div></div>
      <div className="flex flex-1 flex-col justify-end gap-4 overflow-hidden p-5">
        <Skeleton className="h-16 w-2/3 rounded-2xl rounded-bl-sm" />
        <Skeleton className="ml-auto h-20 w-3/5 rounded-2xl rounded-br-sm" />
        <Skeleton className="h-24 w-3/4 rounded-2xl rounded-bl-sm" />
        <Skeleton className="ml-auto h-16 w-1/2 rounded-2xl rounded-br-sm" />
      </div>
      <div className="border-t bg-card p-3"><Skeleton className="h-24 w-full rounded-lg" /></div>
    </div>
  )
}

export function DashboardPageSkeleton() {
  return (
    <div className="grid gap-4" aria-label="Obliczanie statystyk" role="status">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6">{Array.from({ length: 6 }).map((_, index) => <div key={index} className="rounded-lg border bg-card p-4"><Skeleton className="h-3 w-2/3" /><Skeleton className="mt-3 h-7 w-1/2" /></div>)}</div>
      <div className="grid gap-4 lg:grid-cols-2">{Array.from({ length: 4 }).map((_, index) => <div key={index} className="rounded-lg border bg-card p-4"><Skeleton className="h-4 w-1/3" /><Skeleton className="mt-2 h-3 w-1/2" /><div className="mt-5 flex h-60 items-end gap-3">{Array.from({ length: 8 }).map((__, bar) => <Skeleton key={bar} className="flex-1" style={{ height: `${30 + ((bar * 17) % 65)}%` }} />)}</div></div>)}</div>
    </div>
  )
}

export function SettingsPageSkeleton() {
  return (
    <div className="grid gap-4" aria-label="Wczytywanie ustawień" role="status">
      <div className="flex gap-2 overflow-hidden rounded-lg border bg-card p-2">{Array.from({ length: 8 }).map((_, index) => <Skeleton key={index} className="h-8 w-28 shrink-0" />)}</div>
      <div className="rounded-lg border bg-card p-5"><Skeleton className="h-5 w-40" /><Skeleton className="mt-2 h-3 w-80 max-w-full" /><div className="mt-6 grid gap-4 sm:grid-cols-2">{Array.from({ length: 6 }).map((_, index) => <div key={index}><Skeleton className="h-3 w-32" /><Skeleton className="mt-2 h-9 w-full" /></div>)}</div><Skeleton className="ml-auto mt-6 h-8 w-32" /></div>
    </div>
  )
}
