import {
  CircleCheck,
  Clock,
  PauseCircle,
  TriangleAlert,
  type LucideIcon,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { slaStateLabels } from "@/lib/domain/labels"
import type { SlaState } from "@/lib/domain/shared"
import { getEffectiveSlaState } from "@/lib/sla"

const slaConfig: Record<SlaState, { icon: LucideIcon; className: string }> = {
  on_track: { icon: CircleCheck, className: "text-success" },
  at_risk: { icon: Clock, className: "text-warning-foreground" },
  breached: { icon: TriangleAlert, className: "text-destructive" },
  paused: { icon: PauseCircle, className: "text-muted-foreground" },
}

/** Wskaźnik realizacji SLA z opcjonalnym terminem. */
export function SlaIndicator({
  state,
  dueAt,
  className,
}: {
  state: SlaState
  dueAt?: string
  className?: string
}) {
  const effectiveState = getEffectiveSlaState({ state, dueAt })
  const { icon: Icon, className: colorClass } = slaConfig[effectiveState]
  const due = dueAt
    ? new Intl.DateTimeFormat("pl-PL", {
        hour: "2-digit",
        minute: "2-digit",
        day: "2-digit",
        month: "2-digit",
      }).format(new Date(dueAt))
    : null

  return (
    <span className={cn("inline-flex items-center gap-1.5 text-xs font-medium", colorClass, className)}>
      <Icon className="size-3.5" aria-hidden />
      <span>{slaStateLabels[effectiveState]}</span>
      {due && <span className="text-muted-foreground">· {due}</span>}
    </span>
  )
}
