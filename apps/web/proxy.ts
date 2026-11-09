import { NextResponse, type NextRequest } from "next/server"

import { classifyBrowserOrigin } from "./config/same-origin-policy.mjs"
import { developmentPublicOrigin } from "./config/development-proxy.mjs"

/**
 * Same-origin guard for the local/browser ingress. The backend remains
 * authoritative for authentication, authorization and CSRF, but browser
 * cross-origin requests are rejected before /api or /ws are proxied.
 */
export function proxy(request: NextRequest) {
  const originDecision = classifyBrowserOrigin(
    request.url,
    request.headers.get("origin"),
  )

  if (
    (originDecision === "cross-origin" && !isConfiguredDevelopmentIngress(request)) ||
    originDecision === "invalid-origin"
  ) {
    return new NextResponse(null, {
      status: 403,
      headers: {
        "Cache-Control": "no-store",
        Vary: "Origin",
      },
    })
  }

  return NextResponse.next()
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
  matcher: ["/api/:path*", "/ws/:path*"],
}
