// @vitest-environment node

import { NextRequest } from "next/server"
import { describe, expect, test } from "vitest"

import {
  DEFAULT_DEVELOPMENT_BACKEND_ORIGIN,
  developmentProxyRewrites,
  parseDevelopmentBackendOrigin,
} from "../../config/development-proxy.mjs"
import { classifyBrowserOrigin } from "../../config/same-origin-policy.mjs"
import { config as proxyConfig, proxy } from "../../proxy"

describe("same-origin development proxy routing", () => {
  test("routes /api and /ws to the default loopback backend only in development", () => {
    expect(developmentProxyRewrites({}, "development")).toEqual([
      {
        source: "/api/:path*",
        destination: `${DEFAULT_DEVELOPMENT_BACKEND_ORIGIN}/api/:path*`,
      },
      {
        source: "/ws/:path*",
        destination: `${DEFAULT_DEVELOPMENT_BACKEND_ORIGIN}/ws/:path*`,
      },
    ])
    expect(developmentProxyRewrites({}, "production")).toEqual([])
  })

  test("accepts an explicit loopback API port without exposing it to browser config", () => {
    const source = { USI_DEV_BACKEND_ORIGIN: "http://localhost:9080" }

    expect(parseDevelopmentBackendOrigin(source)).toBe("http://localhost:9080")
    expect(developmentProxyRewrites(source, "development")).toEqual([
      {
        source: "/api/:path*",
        destination: "http://localhost:9080/api/:path*",
      },
      {
        source: "/ws/:path*",
        destination: "http://localhost:9080/ws/:path*",
      },
    ])
  })

  test.each([
    "https://127.0.0.1:8080",
    "http://api.internal:8080",
    "http://user:password@127.0.0.1:8080",
    "http://127.0.0.1:8080/admin",
    "http://127.0.0.1:8080?target=other",
    " http://127.0.0.1:8080",
  ])("rejects unsafe development backend origin %s", (origin) => {
    expect(() =>
      parseDevelopmentBackendOrigin({ USI_DEV_BACKEND_ORIGIN: origin }),
    ).toThrow(/loopback HTTP origin/u)
  })

  test("does not evaluate an unused proxy target outside development", () => {
    expect(
      developmentProxyRewrites(
        { USI_DEV_BACKEND_ORIGIN: "https://remote.example.invalid" },
        "production",
      ),
    ).toEqual([])
  })
})

describe("browser Origin guard", () => {
  const request = (
    path: string,
    options: {
      method?: string
      origin?: string
      cookie?: string
    } = {},
  ) => {
    const headers = new Headers()
    if (options.origin !== undefined) headers.set("origin", options.origin)
    if (options.cookie !== undefined) headers.set("cookie", options.cookie)

    return new NextRequest(`http://localhost:3000${path}`, {
      method: options.method ?? "GET",
      headers,
    })
  }

  test("allows the same-origin session-cookie flow without adding CORS headers", () => {
    const incoming = request("/api/v1/cases", {
      method: "POST",
      origin: "http://localhost:3000",
      cookie: "USI_SESSION=opaque-test-session",
    })

    const response = proxy(incoming)

    expect(response.status).toBe(200)
    expect(incoming.cookies.get("USI_SESSION")?.value).toBe("opaque-test-session")
    expect(response.headers.get("access-control-allow-origin")).toBeNull()
  })

  test("rejects a cross-origin CORS preflight without an allow-origin header", () => {
    const response = proxy(
      request("/api/v1/cases", {
        method: "OPTIONS",
        origin: "https://cross-origin.example.invalid",
      }),
    )

    expect(response.status).toBe(403)
    expect(response.headers.get("access-control-allow-origin")).toBeNull()
    expect(response.headers.get("vary")).toBe("Origin")
  })

  test("allows same-origin OPTIONS to continue to the backend", () => {
    const response = proxy(
      request("/api/v1/cases", {
        method: "OPTIONS",
        origin: "http://localhost:3000",
      }),
    )

    expect(response.status).toBe(200)
  })

  test("provider/server callbacks without a browser Origin remain reachable", () => {
    expect(
      proxy(request("/api/v1/provider-callbacks/slack", { method: "POST" })).status,
    ).toBe(200)
  })

  test("blocks malformed Origin values and cross-origin WebSocket requests", () => {
    expect(proxy(request("/api/v1/cases", { origin: "null" })).status).toBe(403)
    expect(
      proxy(request("/ws", { origin: "https://cross-origin.example.invalid" }))
        .status,
    ).toBe(403)
    expect(proxy(request("/ws", { origin: "http://localhost:3000" })).status).toBe(
      200,
    )
  })

  test("the Next proxy matcher covers only API and WebSocket ingress", () => {
    expect(proxyConfig.matcher).toEqual(["/api/:path*", "/ws/:path*"])
    expect(classifyBrowserOrigin("http://localhost:3000/ws", null)).toBe(
      "no-origin",
    )
    expect(
      classifyBrowserOrigin(
        "http://localhost:3000/ws",
        "https://cross-origin.example.invalid",
      ),
    ).toBe("cross-origin")
  })
})
