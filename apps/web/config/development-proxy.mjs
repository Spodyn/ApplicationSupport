// @ts-check

/** @typedef {Readonly<Record<string, string | undefined>>} EnvironmentSource */
/** @typedef {{source: string, destination: string}} Rewrite */

export const DEFAULT_DEVELOPMENT_BACKEND_ORIGIN = "http://127.0.0.1:8080"
export const DEVELOPMENT_BACKEND_ORIGIN_VARIABLE = "USI_DEV_BACKEND_ORIGIN"

const LOOPBACK_HOSTNAMES = new Set(["127.0.0.1", "localhost"])

/**
 * The Next.js development proxy is intentionally local-only. A configurable
 * target is useful when a developer runs the API on another local port, but a
 * remote target would turn the web process into an SSRF-capable open proxy.
 *
 * @param {EnvironmentSource} source
 * @returns {string}
 */
export function parseDevelopmentBackendOrigin(source) {
  const rawValue =
    source[DEVELOPMENT_BACKEND_ORIGIN_VARIABLE] ??
    DEFAULT_DEVELOPMENT_BACKEND_ORIGIN

  if (rawValue !== rawValue.trim() || rawValue.length === 0) {
    throw new Error(
      `${DEVELOPMENT_BACKEND_ORIGIN_VARIABLE} must be a loopback HTTP origin`,
    )
  }

  let parsed
  try {
    parsed = new URL(rawValue)
  } catch {
    throw new Error(
      `${DEVELOPMENT_BACKEND_ORIGIN_VARIABLE} must be a loopback HTTP origin`,
    )
  }

  if (
    parsed.protocol !== "http:" ||
    !LOOPBACK_HOSTNAMES.has(parsed.hostname) ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.pathname !== "/" ||
    parsed.search !== "" ||
    parsed.hash !== "" ||
    parsed.port === "0"
  ) {
    throw new Error(
      `${DEVELOPMENT_BACKEND_ORIGIN_VARIABLE} must be a loopback HTTP origin without credentials, path, query, or fragment`,
    )
  }

  return parsed.origin
}

/**
 * Next.js external rewrites are used only by `next dev`. Production routing is
 * owned by the deployment reverse proxy (Caddy), which sends /api and /ws
 * directly to Spring Boot.
 *
 * Next normalizes rewrite records during startup, so these objects must remain
 * mutable even though callers should treat the returned collection as config.
 *
 * @param {EnvironmentSource} source
 * @param {string | undefined} nodeEnvironment
 * @returns {Rewrite[]}
 */
export function developmentProxyRewrites(source, nodeEnvironment) {
  if (nodeEnvironment !== "development") {
    return []
  }

  const backendOrigin = parseDevelopmentBackendOrigin(source)
  return [
    {
      source: "/api/:path*",
      destination: `${backendOrigin}/api/:path*`,
    },
    {
      source: "/ws/:path*",
      destination: `${backendOrigin}/ws/:path*`,
    },
  ]
}
