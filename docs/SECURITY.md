# Unified Support Inbox - Security Specification

**Status:** FROZEN security baseline for v1

## 1. Principles

- Deny by default.
- Backend is authoritative for authentication, authorization and workflow guards.
- Provider/customer content is untrusted input.
- Secrets never belong in source control, browser bundles, ordinary logs, audit payloads or diagnostics.
- Production credentials and production databases are not available to Codex/CI agents.
- Retry and concurrency must never create duplicate business effects.

## 2. Browser and session security

- Server-side Spring Session JDBC.
- Cookie `USI_SESSION`, `HttpOnly`, `Secure` on staging/prod, `SameSite=Lax`, `Path=/`.
- Idle timeout 12 hours; no remember-me in v1.
- Logout invalidates server session immediately.
- Deactivated/expired users cannot keep using existing sessions.
- Successful password reset invalidates all active sessions.
- State-changing authenticated browser commands are CSRF-protected.
- Same-origin architecture is preferred; CORS deny/off by default, never wildcard for convenience.

Production security headers include restrictive CSP, `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, strict referrer policy and HSTS for one year. `preload`/`includeSubDomains` are not enabled automatically.

## 3. Password and account security

- Argon2id, with implementation-time secure parameters and benchmark.
- Password 12-128 Unicode characters.
- No forced uppercase/digit/symbol composition, scheduled rotation or security questions.
- Generic login failure response; rehash on successful login when parameters become outdated.
- Login abuse baseline: 10 attempts/account/15m + 50/IP/15m, progressive backoff, no permanent account lockout.
- Password-reset abuse: max 5 requests/e-mail/hour + 20/IP/hour, neutral external response.

Invitation/reset tokens use at least 256-bit CSPRNG entropy, store only a hash, are one-time-use, and expire after 24h/30m respectively.

## 4. Authorization

Roles are exactly `USER` and `ADMIN`. Permissions are exactly:

`manage_users`, `manage_integrations`, `manage_sla`, `manage_schedule`, `manage_notifications`, `view_global_statistics`, `reassign_cases`, `force_resolve`, `view_audit`.

- UI visibility never substitutes backend authorization.
- `USER + permission` does not grant an ADMIN operation.
- The last active `ADMIN + manage_users` cannot be disabled, deleted, demoted or stripped of that permission.
- Self-promotion/self-grant is blocked.
- Case read visibility is deployment-wide for active users, while workflow actions enforce ownership/permission rules.

## 5. Content sanitization

Provider/customer content is hostile by default. Normalize rich provider content to safe text/Markdown through a central parser/sanitizer. Never execute raw provider HTML or unsafe URL schemes such as `javascript:`.

## 6. SSRF-safe remote fetch

When fetching provider-hosted content:

- prefer authenticated provider object IDs over arbitrary caller URLs;
- HTTPS and provider-host allowlist;
- validate DNS resolution and final IP;
- block loopback, private, link-local and cloud-metadata ranges;
- max 3 redirects, revalidating each destination;
- strict connect/read timeout and response-size limit.

## 7. Attachment security

Global limits are 25 MiB/file, 10 files and 50 MiB/message; stricter provider rules win. Allowed baseline classes are common images, PDF, text/CSV, OOXML and ZIP. Executables, scripts and active HTML are blocked by default.

Scanning is pluggable; default self-hosted scanner is ClamAV. States are `PENDING`, `CLEAN`, `INFECTED`, `ERROR`; normal send/download is possible only for `CLEAN`, with no bypass for infected/error files.

Archives: encrypted archives blocked; max nesting 3, max 1000 entries, max 250 MiB expanded content and zip-bomb safeguards. Storage keys are random/internal and never derived directly from untrusted filename.

## 8. Provider callbacks

Verify provider-specific signature/secret before business processing, protect against replay where supported, use constant-time comparisons and never log signing material. Do not apply a simplistic low IP rate limit that can drop a valid provider burst; instead use authentication/signature, body-size limits, concurrency/backpressure and per-integration abuse controls.

Slack uses HMAC over raw body with 5-minute replay window. Telegram staging/prod verifies its configured secret-token header. Teams uses its supported authenticated app/RSC model.

## 9. Trusted proxy

Forwarded client-IP headers are trusted only from explicitly configured reverse proxies; direct internet requests cannot spoof administrative client IP by supplying forwarding headers.

## 10. Secrets

Development uses dummy/dev env values and repo contains only `.env.example`. Browser receives only safe public variables.

Staging/production use runtime secret injection/deployment store; integration configuration stores references rather than provider-secret plaintext. Production fails closed if a required secret cannot be resolved. Secrets never enter images, source, ordinary Compose archives, logs, audit or browser bundles.

## 11. Audit security

`audit_events` is append-only from the normal application role; controlled retention is the deletion path. Audit metadata is whitelisted/sanitized and excludes message bodies, attachment content, password material, tokens, cookies, provider secrets and sensitive headers. Hash chaining plus periodic verification provides tamper evidence, not an external immutable ledger.

## 12. CI and agent boundary

Actionable Critical/High security findings block merge. Suppression requires an explicit reason and expiration.

Codex/CI may work in controlled worktrees, tests and fake/sandbox providers, but must not receive production provider secrets, production DB/object-storage credentials or authority for autonomous production cutover, PITR, destructive recovery, secret rotation or DNS/provider callback cutover. Those actions require explicit human approval.
