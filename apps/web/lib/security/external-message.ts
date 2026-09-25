export type ExternalMessagePart =
  | { readonly type: "text"; readonly value: string }
  | { readonly type: "code"; readonly value: string }
  | { readonly type: "link"; readonly label: string; readonly href: string }

/**
 * The only browser rendering contract for customer/provider text.  It returns
 * text tokens, never HTML, so callers cannot accidentally turn provider input
 * into executable markup with dangerouslySetInnerHTML.
 */
export function parseExternalMessage(input: string): readonly ExternalMessagePart[] {
  const text = input.normalize("NFC").replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "")
  const parts: ExternalMessagePart[] = []
  const token = /`([^`\n]{1,2000})`|\[([^\]\n]{1,2000})\]\(([^)\s]{1,2048})\)/g
  let cursor = 0
  for (let match = token.exec(text); match; match = token.exec(text)) {
    appendText(parts, text.slice(cursor, match.index))
    if (match[1] !== undefined) {
      parts.push({ type: "code", value: match[1] })
    } else {
      const href = safeHref(match[3] ?? "")
      if (href) parts.push({ type: "link", label: match[2] ?? "", href })
      else appendText(parts, match[0])
    }
    cursor = match.index + match[0].length
  }
  appendText(parts, text.slice(cursor))
  return parts
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
