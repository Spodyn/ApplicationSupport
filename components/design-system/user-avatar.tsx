import { Ban, Check, Clock3, Minus, type LucideIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { Avatar, AvatarBadge, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { userPresenceLabels } from "@/lib/domain/labels"
import type { User, UserPresence } from "@/lib/domain/types"

const presenceConfig: Record<UserPresence, { className: string; icon: LucideIcon }> = {
  online: { className: "bg-success", icon: Check },
  busy: { className: "bg-destructive", icon: Ban },
  away: { className: "bg-warning text-warning-foreground", icon: Clock3 },
  offline: { className: "bg-muted-foreground text-background", icon: Minus },
}

function initials(fullName: string): string {
  const parts = fullName
    .replace(/^Klient\s*—\s*/i, "")
    .trim()
    .split(/\s+/)
  const letters = parts.slice(0, 2).map((p) => p.charAt(0).toUpperCase())
  return letters.join("") || "?"
}

/** Awatar użytkownika z inicjałami oraz opcjonalnym wskaźnikiem obecności. */
export function UserAvatar({
  user,
  size = "default",
  showPresence = false,
  className,
}: {
  user: Pick<User, "fullName" | "avatarUrl" | "presence">
  size?: "sm" | "default" | "lg"
  showPresence?: boolean
  className?: string
}) {
  const presence = presenceConfig[user.presence]
  const PresenceIcon = presence.icon

  return (
    <Avatar size={size} className={className}>
      {user.avatarUrl && <AvatarImage src={user.avatarUrl} alt={user.fullName} />}
      <AvatarFallback className="bg-accent font-medium text-accent-foreground">
        {initials(user.fullName)}
      </AvatarFallback>
      {showPresence && (
        <AvatarBadge
          className={cn("bg-blend-normal", presence.className)}
          role="img"
          aria-label={`Obecność: ${userPresenceLabels[user.presence]}`}
          title={userPresenceLabels[user.presence]}
        >
          <PresenceIcon aria-hidden />
          <span className="sr-only">{userPresenceLabels[user.presence]}</span>
        </AvatarBadge>
      )}
    </Avatar>
  )
}
