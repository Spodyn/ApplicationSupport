import { afterEach, describe, expect, it } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import ts from "typescript"
import { browserLanguage, createI18n, language, resources, translate } from "@/lib/i18n/catalog"
import { LanguageProvider, LanguageSelector, useT } from "@/lib/i18n/provider"
import { formatDate, formatDuration, formatNumber, formatRelative } from "@/lib/format"
import { ApiHttpError } from "@/lib/services/api/http-transport"
import { userError } from "@/lib/i18n/errors"
import { Input } from "@/components/ui/input"
import { inboxStatusLabels } from "@/lib/domain/inbox"
import { deliveryStatusLabels, slaStateLabels } from "@/lib/domain/labels"

afterEach(() => { document.cookie = "usi-language=; Max-Age=0; Path=/" })
function Probe() { const t = useT(); return <><p>{t("Czaty")}</p><p>{t("Rozwiązane")}</p><LanguageSelector /></> }
describe("Polish and English interface catalogs", () => {
  it("switches existing content immediately and persists the explicit choice", () => {
    const view = render(<LanguageProvider initialLanguage="pl"><Probe /></LanguageProvider>)
    expect(screen.getByText("Czaty")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Język interfejsu"), { target: { value: "en" } })
    expect(screen.getByText("Chats")).toBeInTheDocument()
    expect(screen.getByText("Resolved")).toBeInTheDocument()
    expect(document.documentElement.lang).toBe("en")
    expect(browserLanguage()).toBe("en")
    view.unmount()
    render(<LanguageProvider initialLanguage={browserLanguage()}><Probe /></LanguageProvider>)
    expect(screen.getByText("Chats")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Interface language"), { target: { value: "pl" } })
    expect(screen.getByText("Czaty")).toBeInTheDocument()
  })
  it("defaults to Polish and uses isolated language instances", () => {
    expect(language()).toBe("pl")
    expect(language("de")).toBe("pl")
    const english = createI18n("en"), polish = createI18n("pl")
    expect(translate("Czaty", undefined, "en")).toBe("Chats")
    expect(english.language).toBe("en")
    expect(polish.language).toBe("pl")
  })
  it("has complete, nonempty catalogs and matching interpolation placeholders", () => {
    expect(Object.keys(resources.en).sort()).toEqual(Object.keys(resources.pl).sort())
    const keys = Object.values(resources.pl).flatMap((messages) => Object.keys(messages))
    expect(new Set(keys).size).toBe(keys.length)
    for (const [namespace, messages] of Object.entries(resources.pl)) {
      const english = resources.en[namespace as keyof typeof resources.en] as Record<string, string>
      expect(Object.keys(english).sort(), namespace).toEqual(Object.keys(messages).sort())
      for (const [key, value] of Object.entries(messages)) {
        expect(value.trim(), key).not.toBe("")
        expect(english[key]?.trim(), key).not.toBe("")
        expect(english[key]?.match(/\{\{\w+\}\}/g)?.sort(), key).toEqual(value.match(/\{\{\w+\}\}/g)?.sort())
      }
    }
  })
  it("has translations for every statically referenced interface key", () => {
    const known = new Set(Object.values(resources.pl).flatMap((messages) => Object.keys(messages)))
    const missing: string[] = []
    for (const folder of ["app", "components", "lib/services", "lib/i18n"]) {
      for (const entry of readdirSync(folder, { recursive: true }).map(String).filter((file) => /\.(tsx?|ts)$/.test(file))) {
        const file = path.join(folder, entry)
        const source = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true)
        function walk(node: ts.Node) {
          if (ts.isCallExpression(node) && ["t", "translate"].includes(node.expression.getText(source)) && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
            const key = node.arguments[0].text
            if (!known.has(key)) missing.push(`${file}: ${key}`)
          }
          ts.forEachChild(node, walk)
        }
        walk(source)
      }
    }
    expect(missing).toEqual([])
    for (const labels of [inboxStatusLabels, deliveryStatusLabels, slaStateLabels]) {
      for (const key of Object.values(labels)) expect(known.has(key), key).toBe(true)
    }
  })
  it("localizes native validation without disabling required/email constraints", () => {
    render(<LanguageProvider initialLanguage="en"><Input aria-label="test-email" required type="email" /><LanguageSelector /></LanguageProvider>)
    const input = screen.getByLabelText("test-email") as HTMLInputElement
    expect(input.checkValidity()).toBe(false)
    expect(input.validationMessage).toBe("This field is required.")
    fireEvent.input(input, { target: { value: "invalid-email" } })
    expect(input.checkValidity()).toBe(false)
    expect(input.validationMessage).toBe("Enter a valid email address.")
    fireEvent.input(input, { target: { value: "agent@example.test" } })
    expect(input.checkValidity()).toBe(true)
    fireEvent.change(screen.getByLabelText("Interface language"), { target: { value: "pl" } })
    fireEvent.input(input, { target: { value: "" } })
    expect(input.checkValidity()).toBe(false)
    expect(input.validationMessage).toBe("To pole jest wymagane.")
  })
  it("formats dates, durations, numbers and safe problem codes in the selected language", () => {
    const date = "2026-11-10T12:00:00Z"
    const plDate = formatDate(date), plDuration = formatDuration(80), plNumber = formatNumber(1234.5)
    document.cookie = "usi-language=en; Path=/"
    expect(formatDate(date)).toContain("November")
    expect(formatDate(date)).not.toBe(plDate)
    expect(formatDuration(80)).not.toBe(plDuration)
    expect(formatNumber(1234.5)).not.toBe(plNumber)
    expect(formatRelative(new Date(Date.now() - 120_000))).toBe("2 minutes ago")
    const problem = new ApiHttpError(403, {code: "ACCESS_DENIED", status: 403, detail: "technical detail", title: "Access denied", correlationId: "test"})
    expect(userError(problem)).toBe("You do not have permission to perform this action.")
    expect(problem.message).toBe("technical detail")
  })
})
