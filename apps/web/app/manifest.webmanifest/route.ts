import { pwaManifest } from "@/lib/pwa-manifest"

export const dynamic = "force-static"

/** Keep the legacy URL without Next.js injecting a second manifest link. */
export function GET() {
  return new Response(JSON.stringify(pwaManifest("pl")), {
    headers: { "Content-Type": "application/manifest+json", "Cache-Control": "public, max-age=86400" },
  })
}
