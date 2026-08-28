import type { Channel as ApiChannel } from "@usi/api-client/generated/types.gen"
import type { Channel } from "@/lib/domain/shared"

export function mapApiChannel(channel: ApiChannel): Channel {
  switch (channel) {
    case "SLACK":
      return "slack"
    case "TEAMS":
      return "teams"
    case "TELEGRAM":
      return "telegram"
    default:
      return assertNever(channel)
  }
}

function assertNever(value: never): never {
  throw new Error(`Unsupported API channel: ${String(value)}`)
}
