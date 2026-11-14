import emojiData from "./slack-emoji.json"

const slackEmoji = new Map(Object.entries(emojiData))

export type ExternalMessagePart =
  | { readonly type: "text"; readonly value: string }
  | { readonly type: "code"; readonly value: string }
  | { readonly type: "link"; readonly label: string; readonly href: string }

/**
 * The only browser rendering contract for customer/provider text.  It returns
 * text tokens, never HTML, so callers cannot accidentally turn provider input
 * into executable markup with dangerouslySetInnerHTML.
 */
export function parseExternalMessage(input: string, slack = false): readonly ExternalMessagePart[] {
  const text = input.normalize("NFC").replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "")
  const parts: ExternalMessagePart[] = []
  const token = slack
    ? /```([\s\S]*?)(?:```|$)|`([^`\n]+)`|\[([^\]\n]{1,2000})\]\(([^)\s]{1,2048})\)|<((?:https?:\/\/|mailto:)[^<>|\s]+)(?:\|([^<>\n]+))?>|https?:\/\/[^\s<>]+/gi
    : /`([^`\n]{1,2000})`|\[([^\]\n]{1,2000})\]\(([^)\s]{1,2048})\)/g
  let cursor = 0
  for (let match = token.exec(text); match; match = token.exec(text)) {
    appendText(parts, renderText(text.slice(cursor, match.index), slack))
    const [, first, second, third, fourth, fifth, sixth] = match
    const code = slack ? first ?? second : first
    if (code !== undefined) {
      parts.push({ type: "code", value: code })
    } else {
      const rawHref = slack ? fourth ?? fifth : third
      const href = rawHref && safeHref(rawHref)
      if (href) {
        const label = slack ? third ?? sixth : second
        parts.push({ type: "link", label: label === undefined ? rawHref : renderText(label, slack), href })
      } else appendText(parts, match[0])
    }
    cursor = match.index + match[0].length
  }
  appendText(parts, renderText(text.slice(cursor), slack))
  return parts
}

// Source: iamcal/emoji-data at e77759c296ed37beb0dbd63908b43d8619509c6d.
// The JSON retains short_names → unified Unicode only. See slack-emoji.LICENSE.
function renderText(value: string, slack: boolean): string {
  if (!slack) return value
  return value.replace(/:([a-z0-9_+-]+):/gi, (shortcode, name: string) => slackEmoji.get(name) ?? shortcode)
}

export function slackMessagePreview(value: string): string {
  return parseExternalMessage(value, true).map(part => part.type === "link" ? part.label : part.value).join("")
}

function appendText(parts: ExternalMessagePart[], value: string) {
  if (!value) return
  const previous = parts.at(-1)
  if (previous?.type === "text") {
    parts[parts.length - 1] = { type: "text", value: previous.value + value }
    return
  }
  parts.push({ type: "text", value })
}

function safeHref(value: string): string | undefined {
  try {
    const url = new URL(value)
    return ["https:", "http:", "mailto:"].includes(url.protocol) ? url.href : undefined
  } catch {
    return undefined
  }
}
