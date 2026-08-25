import { Ban, CircleCheckBig, CircleDot, MessageCircleQuestion, MoreHorizontal, ShieldCheck, type LucideIcon } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { inboxStatusLabels, type InboxStatus } from "@/lib/domain/inbox"
import { cn } from "@/lib/utils"

const statusConfig: Record<InboxStatus, { icon: LucideIcon; className: string }> = {
  new: { icon: CircleDot, className: "bg-info/10 text-info" },
  verification: { icon: ShieldCheck, className: "bg-primary/10 text-primary" },
  waiting_for_customer: { icon: MessageCircleQuestion, className: "bg-warning/15 text-warning-foreground" },
  partially_ignored: { icon: MoreHorizontal, className: "bg-channel-teams/10 text-channel-teams" },
  ignored: { icon: Ban, className: "bg-destructive/10 text-destructive" },
  resolved: { icon: CircleCheckBig, className: "bg-success/10 text-success" },
}

export function InboxStatusBadge({ status, compact = false, className }: { status: InboxStatus; compact?: boolean; className?: string }) {
  const config = statusConfig[status]
  const Icon = config.icon
  return (
    <Badge variant="secondary" className={cn("border-transparent", config.className, compact && "max-w-40 px-1.5 text-[10px]", className)} title={inboxStatusLabels[status]}>
      <Icon aria-hidden />
      <span className={compact ? "truncate" : undefined}>{inboxStatusLabels[status]}</span>
    </Badge>
  )
}
