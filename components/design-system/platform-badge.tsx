import { Hash, MessagesSquare, Send, type LucideIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { channelLabels } from "@/lib/domain/labels"
import type { Channel } from "@/lib/domain/shared"

/**
 * Odznaki platform (Slack, Microsoft Teams, Telegram).
 * Ikony są neutralne — nie odwzorowują znaków towarowych producentów.
 */
export const channelIcons: Record<Channel, LucideIcon> = {
  slack: Hash,
  teams: MessagesSquare,
  telegram: Send,
}

const channelStyles: Record<Channel, string> = {
  slack: "bg-channel-slack/10 text-channel-slack",
  teams: "bg-channel-teams/10 text-channel-teams",
  telegram: "bg-channel-telegram/10 text-channel-telegram",
}

const channelIconStyles: Record<Channel, string> = {
  slack: "bg-channel-slack/12 text-channel-slack",
  teams: "bg-channel-teams/12 text-channel-teams",
  telegram: "bg-channel-telegram/12 text-channel-telegram",
}

export function PlatformBadge({
  channel,
  showLabel = true,
  className,
}: {
  channel: Channel
  showLabel?: boolean
  className?: string
}) {
  const Icon = channelIcons[channel]
  return (
    <span
      className={cn(
        "inline-flex h-5 w-fit items-center gap-1 rounded-md px-1.5 text-xs font-medium",
        channelStyles[channel],
        className,
      )}
    >
      <Icon className="size-3" aria-hidden />
      {showLabel && <span>{channelLabels[channel]}</span>}
    </span>
  )
}

/** Kwadratowa ikona kanału — używana na listach i w nagłówkach wątków. */
export function PlatformIcon({
  channel,
  className,
}: {
  channel: Channel
  className?: string
}) {
  const Icon = channelIcons[channel]
  return (
    <span
      className={cn(
        "inline-flex size-7 shrink-0 items-center justify-center rounded-md",
        channelIconStyles[channel],
        className,
      )}
      title={channelLabels[channel]}
    >
      <Icon className="size-3.5" aria-hidden />
      <span className="sr-only">{channelLabels[channel]}</span>
    </span>
  )
}
