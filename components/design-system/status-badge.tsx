import { cn } from "@/lib/utils"
import { Badge } from "@/components/ui/badge"
import { casePriorityLabels, caseStatusLabels } from "@/lib/domain/labels"
import type { CasePriority, CaseStatus } from "@/lib/domain/types"
import { Archive, ArrowDown, ArrowUp, CircleCheckBig, CircleDot, CirclePlay, Clock3, Minus, PauseCircle, TriangleAlert, type LucideIcon } from "lucide-react"

const statusStyles: Record<CaseStatus, { badge: string; icon: LucideIcon }> = {
  new: { badge: "bg-info/12 text-info", icon: CircleDot },
  open: { badge: "bg-primary/12 text-primary", icon: CirclePlay },
  pending: { badge: "bg-warning/15 text-warning-foreground", icon: Clock3 },
  on_hold: { badge: "bg-muted text-muted-foreground", icon: PauseCircle },
  resolved: { badge: "bg-success/12 text-success", icon: CircleCheckBig },
  closed: { badge: "bg-muted text-muted-foreground", icon: Archive },
}

export function StatusBadge({
  status,
  className,
}: {
  status: CaseStatus
  className?: string
}) {
  const style = statusStyles[status]
  const Icon = style.icon
  return (
    <Badge
      variant="secondary"
      className={cn("gap-1.5 border-transparent", style.badge, className)}
    >
      <Icon aria-hidden />
      {caseStatusLabels[status]}
    </Badge>
  )
}

const priorityStyles: Record<CasePriority, { className: string; icon: LucideIcon }> = {
  low: { className: "bg-muted text-muted-foreground", icon: ArrowDown },
  medium: { className: "bg-info/12 text-info", icon: Minus },
  high: { className: "bg-warning/15 text-warning-foreground", icon: ArrowUp },
  urgent: { className: "bg-destructive/12 text-destructive", icon: TriangleAlert },
}

export function PriorityBadge({
  priority,
  className,
}: {
  priority: CasePriority
  className?: string
}) {
  const config = priorityStyles[priority]
  const Icon = config.icon
  return (
    <Badge
      variant="secondary"
      className={cn("border-transparent", config.className, className)}
    >
      <Icon aria-hidden />
      {casePriorityLabels[priority]}
    </Badge>
  )
}
