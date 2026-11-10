import type { MetadataRoute } from "next"
import { translate, type Language } from "@/lib/i18n/catalog"

/** Public, locale-keyed install metadata. Never includes request/user data. */
export function pwaManifest(language: Language): MetadataRoute.Manifest {
  return {
    name: "Unified Support Inbox",
    short_name: "Support Inbox",
    description: translate("Ujednolicona skrzynka wsparcia dla Slacka, Microsoft Teams i Telegrama.", undefined, language),
    start_url: "/cases",
    scope: "/",
    display: "standalone",
    background_color: "#f8fafc",
    theme_color: "#2563a8",
    lang: language,
    categories: ["business", "productivity"],
    icons: [
      { src: "/pwa/icon-192", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/pwa/icon-512", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/pwa/icon-maskable", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  }
}
