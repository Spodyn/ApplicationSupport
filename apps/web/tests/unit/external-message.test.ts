import { describe, expect, test } from "vitest"

import { parseExternalMessage, slackMessagePreview } from "../../lib/security/external-message"

describe("external message rendering policy", () => {
  test("preserves text, code, unicode and approved links without producing HTML", () => {
    expect(parseExternalMessage("Zażółć `status=503` [portal](https://example.invalid/a)")).toEqual([
      { type: "text", value: "Zażółć " },
      { type: "code", value: "status=503" },
      { type: "text", value: " " },
      { type: "link", label: "portal", href: "https://example.invalid/a" },
    ])
  })

  test("keeps executable markup and unsafe link schemes as inert text", () => {
    const parts = parseExternalMessage('<img src=x onerror=alert(1)> [x](javascript:alert(1))\u0000')

    expect(parts).toEqual([{ type: "text", value: '<img src=x onerror=alert(1)> [x](javascript:alert(1))' }])
  })
  test("renders Slack aliases while preserving Unicode and unknown names", () => {
    expect(slackMessagePreview(":eyes: :thumbsup: :not_a_real_alias: 👩‍💻")).toBe("👀 👍 :not_a_real_alias: 👩‍💻")
    expect(parseExternalMessage(":eyes:")).toEqual([{ type: "text", value: ":eyes:" }])
  })

  test("keeps shortcodes literal in code and URL destinations while rendering link labels", () => {
    const url = "https://example.invalid/:eyes:"
    expect(parseExternalMessage(`\`:eyes:\` \`\`\`
:eyes:
\`\`\` <${url}|:eyes:> ${url}`, true)).toEqual([
      { type: "code", value: ":eyes:" },
      { type: "text", value: " " },
      { type: "code", value: "\n:eyes:\n" },
      { type: "text", value: " " },
      { type: "link", label: "👀", href: url },
      { type: "text", value: ` ${url}` },
    ])
    expect(slackMessagePreview("[label](javascript:evil) \`\`\` :eyes:")).toBe("[label](javascript:evil)  :eyes:")
    expect(slackMessagePreview("<https://example.invalid/:eyes:>")).toBe("https://example.invalid/:eyes:")
    expect(slackMessagePreview("[:eyes:](mailto:support@example.invalid)")).toBe("👀")
  })
  test("does not make rejected Slack link destinations actionable", () => {
    expect(parseExternalMessage("[bad](javascript:evil) \`code\` :eyes:", true)).toEqual([
      { type: "text", value: "[bad](javascript:evil) " },
      { type: "code", value: "code" },
      { type: "text", value: " 👀" },
    ])
    expect(slackMessagePreview("<mailto:x@example.invalid|alias> <https://example.invalid/:eyes:>"))
      .toBe("alias https://example.invalid/:eyes:")
  })
})
