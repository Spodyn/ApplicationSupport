import { language, languages } from "@/lib/i18n/catalog"
import { pwaManifest } from "@/lib/pwa-manifest"

export const dynamic = "force-static"
export const dynamicParams = false
export function generateStaticParams() { return languages.map((locale) => ({ locale })) }

export async function GET(_request: Request, { params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params
  if (!languages.some((supported) => supported === locale)) return new Response(null, { status: 404 })
  return new Response(JSON.stringify(pwaManifest(language(locale))), {
    headers: { "Content-Type": "application/manifest+json", "Cache-Control": "public, max-age=86400" },
  })
}
