// @ts-check

/** @typedef {`/${string}`} SameOriginPath */
/** @typedef {Readonly<{apiBaseUrl: SameOriginPath, websocketBaseUrl: SameOriginPath}>} PublicEnvironment */
/** @typedef {Readonly<Record<string, string | undefined>>} PublicEnvironmentSource */

/** @type {readonly ["NEXT_PUBLIC_API_BASE_URL", "NEXT_PUBLIC_WS_BASE_URL"]} */
export const PUBLIC_ENVIRONMENT_VARIABLE_NAMES = Object.freeze([
  "NEXT_PUBLIC_API_BASE_URL",
  "NEXT_PUBLIC_WS_BASE_URL",
])

/** @type {ReadonlySet<string>} */
const allowedPublicNames = new Set(PUBLIC_ENVIRONMENT_VARIABLE_NAMES)

/**
 * @param {string} name
 * @param {string | undefined} value
 * @param {`/${string}`} expectedValue
 * @returns {`/${string}`}
 */
function requireSameOriginPath(name, value, expectedValue) {
  if (value === undefined || value.length === 0) {
    throw new Error(`Missing required browser-public variable: ${name}`)
  }
  if (
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value !== expectedValue ||
    /[\s?#\\%]/u.test(value)
  ) {
    throw new Error(`${name} must equal the reviewed same-origin path ${expectedValue}`)
  }

  let decoded = ""
  try {
    decoded = decodeURIComponent(value)
  } catch {
    throw new Error(`${name} contains invalid percent encoding`)
  }
  if (decoded.split("/").some((segment) => segment === "." || segment === "..")) {
    throw new Error(`${name} must not contain path traversal segments`)
  }
  return /** @type {SameOriginPath} */ (value)
}

/**
 * Parse the complete process environment with a deny-by-default public
 * allowlist. Error messages contain variable names only, never values.
 *
 * @param {PublicEnvironmentSource} source
 * @returns {Readonly<PublicEnvironment>}
 */
export function parsePublicEnvironment(source) {
  const unknownPublicNames = Object.keys(source).filter(
    (name) => name.startsWith("NEXT_PUBLIC_") && !allowedPublicNames.has(name),
  )
  if (unknownPublicNames.length > 0) {
    throw new Error(
      `Unreviewed browser-public variable is forbidden: ${unknownPublicNames.sort().join(", ")}`,
    )
  }

  return Object.freeze({
    apiBaseUrl: requireSameOriginPath(
      "NEXT_PUBLIC_API_BASE_URL",
      source.NEXT_PUBLIC_API_BASE_URL,
      "/api/v1",
    ),
    websocketBaseUrl: requireSameOriginPath(
      "NEXT_PUBLIC_WS_BASE_URL",
      source.NEXT_PUBLIC_WS_BASE_URL,
      "/ws",
    ),
  })
}
