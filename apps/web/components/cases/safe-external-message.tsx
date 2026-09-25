import { Fragment } from "react"

import { parseExternalMessage } from "@/lib/security/external-message"

/** Renders external/provider text without accepting provider HTML. */
export function SafeExternalMessage({ content }: { content: string }) {
  return (
    <>
      {parseExternalMessage(content).map((part, index) => {
        if (part.type === "code") {
          return <code key={index} className="rounded bg-black/20 px-1 font-mono text-[0.9em]">{part.value}</code>
        }
        if (part.type === "link") {
          return <a key={index} href={part.href} target="_blank" rel="noreferrer noopener" className="underline underline-offset-2">{part.label}</a>
        }
        return <Fragment key={index}>{part.value}</Fragment>
      })}
    </>
  )
}
