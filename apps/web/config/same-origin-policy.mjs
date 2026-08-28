// @ts-check

/** @typedef {"no-origin" | "same-origin" | "cross-origin" | "invalid-origin"} BrowserOriginDecision */

/**
 * Classify the browser Origin header without trusting X-Forwarded-* input in
 * application code. Missing Origin is allowed for non-browser/server-to-server
 * traffic such as provider callbacks. A present Origin must be a syntactically
 * valid HTTP(S) origin and match the incoming request origin exactly.
 *
 * @param {string} requestUrl
 * @param {string | null} originHeader
 * @returns {BrowserOriginDecision}
 */
export function classifyBrowserOrigin(requestUrl, originHeader) {
  if (originHeader === null) {
    return "no-origin"
  }

  if (originHeader.length === 0 || originHeader !== originHeader.trim()) {
    return "invalid-origin"
  }

  let request
  let origin
  try {
    request = new URL(requestUrl)
    origin = new URL(originHeader)
  } catch {
    return "invalid-origin"
  }

  if (
    !["http:", "https:"].includes(request.protocol) ||
    !["http:", "https:"].includes(origin.protocol) ||
    origin.username !== "" ||
    origin.password !== "" ||
    origin.pathname !== "/" ||
    origin.search !== "" ||
    origin.hash !== ""
  ) {
    return "invalid-origin"
  }

  return origin.origin === request.origin ? "same-origin" : "cross-origin"
}

/**
 * @param {string} requestUrl
 * @param {string | null} originHeader
 * @returns {boolean}
 */
export function shouldRejectBrowserOrigin(requestUrl, originHeader) {
  const decision = classifyBrowserOrigin(requestUrl, originHeader)
  return decision === "cross-origin" || decision === "invalid-origin"
}
