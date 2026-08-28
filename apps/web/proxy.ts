import { NextResponse, type NextRequest } from "next/server"

import { classifyBrowserOrigin } from "./config/same-origin-policy.mjs"

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
    originDecision === "cross-origin" ||
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

export const config = {
  matcher: ["/api/:path*", "/ws/:path*"],
}
