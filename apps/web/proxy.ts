import { NextResponse, type NextRequest } from "next/server"

import { classifyBrowserOrigin } from "./config/same-origin-policy.mjs"
import { developmentPublicOrigin } from "./config/development-proxy.mjs"
import { browserContentSecurityPolicy } from "./config/browser-security.mjs"

/**
 * Same-origin guard for the local/browser ingress. The backend remains
 * authoritative for authentication, authorization and CSRF, but browser
 * cross-origin requests are rejected before /api or /ws are proxied.
 */
export function proxy(request: NextRequest) {
  const isApi = request.nextUrl.pathname.startsWith('/api/') || request.nextUrl.pathname === '/ws' || request.nextUrl.pathname.startsWith('/ws/')
  const originDecision = classifyBrowserOrigin(
    request.url,
    request.headers.get("origin"),
  )

  if (
    isApi && ((originDecision === "cross-origin" && !isConfiguredDevelopmentIngress(request)) ||
    originDecision === "invalid-origin")
  ) {
    return new NextResponse(null, {
      status: 403,
      headers: {
        "Cache-Control": "no-store",
        Vary: "Origin",
      },
    })
  }

  if (isApi) return NextResponse.next()

  const nonce = Buffer.from(crypto.randomUUID()).toString('base64')
  const policy = browserContentSecurityPolicy(nonce, process.env.NODE_ENV === 'development')
  const requestHeaders = new Headers(request.headers)
  // Overwrite caller-provided values before Next.js derives script nonces.
  requestHeaders.set('x-nonce', nonce)
  requestHeaders.set('Content-Security-Policy', policy)
  const response = NextResponse.next({ request: { headers: requestHeaders } })
  response.headers.set('Content-Security-Policy', policy)
  response.headers.set('Cache-Control', 'private, no-store')
  response.headers.set('X-Frame-Options', 'DENY')
  response.headers.set('X-Content-Type-Options', 'nosniff')
  response.headers.set('Referrer-Policy', 'no-referrer')
  response.headers.set('Permissions-Policy', 'camera=(), microphone=(), geolocation=()')
  if (request.nextUrl.protocol === 'https:') {
    response.headers.set('Strict-Transport-Security', 'max-age=31536000')
  }
  return response
}

// A local HTTPS tunnel terminates TLS before Next.js. Accept only the explicitly
// configured public origin and its exact Host; never trust arbitrary forwarded headers.
function isConfiguredDevelopmentIngress(request: NextRequest): boolean {
  if (process.env.NODE_ENV !== "development" || !process.env.USI_DEV_PUBLIC_ORIGIN) return false
  const configured = developmentPublicOrigin(process.env)!
  return request.headers.get("origin") === configured.origin
    && request.headers.get("host") === configured.host
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|sw.js|pwa/|manifest.webmanifest).*)'],
}
