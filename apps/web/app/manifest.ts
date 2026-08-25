import type { MetadataRoute } from "next"

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Unified Support Inbox",
    short_name: "Support Inbox",
    description: "Ujednolicona skrzynka wsparcia dla Slacka, Microsoft Teams i Telegrama.",
    start_url: "/cases",
    scope: "/",
    display: "standalone",
    background_color: "#f8fafc",
    theme_color: "#2563a8",
    lang: "pl",
    categories: ["business", "productivity"],
    icons: [
      { src: "/pwa/icon-192", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/pwa/icon-512", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/pwa/icon-maskable", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  }
}
