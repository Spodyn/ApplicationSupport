import { createInstance } from "i18next"
import pl from "./pl.json"
import en from "./en.json"

export const languages = ["pl", "en"] as const
export type Language = (typeof languages)[number]
export const LANGUAGE_COOKIE = "usi-language"
export const resources = { pl, en }
export function language(value?: string): Language { return value === "en" ? "en" : "pl" }
export function browserLanguage(): Language {
  if (typeof document === "undefined") return "pl"
  return language(document.cookie.split(";").map((item) => item.trim()).find((item) => item.startsWith(`${LANGUAGE_COOKIE}=`))?.split("=")[1])
}
export function locale(value: Language = browserLanguage()): string { return value === "en" ? "en-GB" : "pl-PL" }

// Source-text keys are grouped into feature namespaces. Only interface copy is
// registered here; provider content, customer/channel names and IDs never enter t().
const namespaces = new Map<string, string>()
for (const [ns, messages] of Object.entries(pl)) for (const key of Object.keys(messages)) namespaces.set(key, ns)
export function translationOptions(key: string, values?: Record<string, unknown>) {
  return { ns: namespaces.get(key) ?? "common", ...values }
}
export function createI18n(initialLanguage: Language) {
  const instance = createInstance()
  void instance.init({ resources, lng: initialLanguage, fallbackLng: "pl", supportedLngs: [...languages],
    defaultNS: "common", keySeparator: false, nsSeparator: false, initAsync: false,
    interpolation: { escapeValue: false }, returnNull: false })
  return instance
}
// Immutable instances for formatting/errors outside React; never mutate a shared
// server singleton's language between requests.
const standalone = { pl: createI18n("pl"), en: createI18n("en") }
export function translate(key: string, values?: Record<string, unknown>, selectedLanguage = browserLanguage()): string {
  return String(standalone[selectedLanguage].t(key, translationOptions(key, values)))
}
