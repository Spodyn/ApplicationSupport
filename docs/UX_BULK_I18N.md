# Interface languages and bulk Case commands

Owner request, 2026-11-10: implement the eight grouped UX improvements on top of security PR #147 and regression PR #148. Starting main is `ee1e84ff9e56d106b40a6afa1d902489063cd523`. Live Jira search returned 403 (connector not installed); no Jira status or decision was fabricated. This explicit grouped owner request authorizes one feature branch for this scope.

## Languages

`i18next` and `react-i18next` use feature namespaces in `apps/web/lib/i18n/{pl,en}.json`. Keys are Polish source copy or semantic identifiers for ambiguous labels, with key/namespace separators disabled so punctuation remains literal. `useT` subscribes components to language changes; shared domain label maps remain source keys. Never translate arbitrary data at generic table/filter boundaries.

Polish is the default, including for unknown cookie values. The user menu, General Settings and public authentication pages expose a language selector. `usi-language=pl|en` is a one-year preference cookie with Path=/, SameSite=Lax and Secure on HTTPS. It contains no credentials and changes no authentication/CSRF policy. Preference persistence is browser-specific; it does not sync accounts/devices or change the organisation's stored policy. Root layout reads the cookie before rendering. Each React provider owns its instance; server requests never mutate a shared instance's language. Non-React formatting uses immutable per-language instances and Intl.

Translate interface labels, stable problem codes, native form validation, status/delivery/SLA projections, notifications, dates and durations. Keep CaseStatus/API enums, IDs, customer/channel/user names, provider messages, calendar notes and OOO templates unchanged. Deleted-message tombstones are localized from the separate deleted flag. Additional languages require a catalog, supported-language registration and completeness/formatting tests.

PWA install metadata uses public static `/pwa/manifest/pl` and `/pwa/manifest/en` variants. Root metadata and immediate language switching select the appropriate link. The legacy `/manifest.webmanifest` URL remains Polish-compatible through an explicit route handler, avoiding an automatic second manifest link. Static cache v2 precaches both language variants; their contents never depend on authentication, cookies or user data. Existing API/authenticated-request cache exclusions remain unchanged.

## Bulk API and transactions

POST `/api/v1/cases/bulk` requires a refreshed authenticated active session, CSRF and Idempotency-Key. OpenAPI and the generated client describe this request:

```json
{"caseIds":["00000000-0000-4000-8000-000000000001"],"action":"RESOLVE","targetUserId":null}
```

Select 1–100 distinct non-null UUIDs. ASSIGN requires a target UUID; other actions forbid it. Invalid/unknown actions and malformed inputs are 400. Assignment requires ADMIN and explicit reassign_cases; an unauthorized batch is 403 before effects. Other commands use ordinary workflow rules.

| Action | Existing rules reused |
| --- | --- |
| ASSIGN | Assign eligible unowned Cases or Reassign verification; active target and lifetime Ignore history checked separately for each Case |
| RESOLVE | Normal owner-only Resolve; never substitute Force Resolve |
| IGNORE | Actor's normal server-weighted vote; threshold and lifetime restrictions preserved |
| CLAIM | Normal Claim, including ownership and lifetime voter restrictions |

HTTP 200 returns ordered items with caseId, status (SUCCEEDED, SKIPPED, FAILED) and stable code. Unavailable actions return SKIPPED/ACTION_UNAVAILABLE. Missing records and other controlled 4xx outcomes are skipped with their existing problem code. Unexpected failures roll back that item and return FAILED/APPLICATION_FAILURE without exception/provider text. A later failure does not undo successful earlier items. The bulk item owns its idempotency namespace; it never replays an unrelated single-Case command with the same external key.

A short transaction first registers the immutable full request in the existing 24-hour idempotency store. Reusing a key for another ordered selection, action or target conflicts before effects. Items run sequentially in independent transactions: lock the Case, obtain authoritative availability, invoke the existing transaction-mandatory business implementation, and atomically commit result, business changes, audit and outbox. Exceptions roll back first; a fresh transaction persists the sanitized skipped/failed outcome. A competing replay uses the first committed outcome. Interrupted requests resume missing results. If the database is unavailable, even outcome persistence can fail; retry the identical request/key to recover committed results. A network failure never implies all-or-nothing behaviour. During the existing 24-hour window, recorded skipped/failed outcomes remain unchanged even if eligibility changes; a deliberate new operation uses a new key.

No new database table or migration is needed. No provider HTTP runs in these transactions. Existing audit/outbox/realtime paths are reused.

## Selection and presentation

Individual checkboxes support keyboard selection across loaded pages. Select visible selects loaded rows. Select all matching sequentially enumerates filtered pages and freezes explicit IDs, up to 1000. Larger results require narrower filters and are not silently truncated. Visible selection and additional checkboxes disable with explicit limit feedback; overlapping pages are deduplicated before counting/selection. All-matching enumeration has a defensive 21-page budget (50 rows/page plus excess detection), including fresh empty/overlapping cursors. This is an enumerated selection, not a live predicate: later incoming Cases are not added. Changing filters clears selection. The adapter submits sequential batches of at most 100 with stable batch-specific keys. A lost response retains the frozen selection/key for retry; prior batch effects replay. Confirmation includes operation/count and terminal resolution/Ignore consequences. Per-item results clear selection and invalidate lists, details, messages, activity, statistics and workload queries.

Customer/Channel controls are removed only from inbox Case filters; compatible API/model fields remain. This baseline has no persisted inbox-filter UI to migrate. Search, provider/owner/status filters and pagination remain. The SLA shortcut retains existing attention-filter behaviour without the chevron; SLA calculations and semantics are unchanged.

Resolved indicators are neutral gray; breach/error warnings remain red. Shared and native buttons have hover, focus-visible, pressed and disabled feedback with reduced-motion support. Enter sends, Shift+Enter inserts a newline, and IME confirmation/repeated keydown are excluded. A synchronous submission lock supplements existing idempotency. Draft/files are locked while submitting and retained on failure. The conversation flex chain, bubbles, metadata and attachment names constrain width and wrap unbroken text with overflow-wrap:anywhere, preserving newlines, copying and safe links.

## Verification

Actual results and CI/merge evidence are in [UX_VALIDATION.md](UX_VALIDATION.md). Existing CI/security gates are unchanged. Tests cover catalog completeness/placeholders, switching/persistence/SSR/formatting, native validation, sequential bounded batches, keyboard/IME/attachment send, wrapping at three widths, filters, bulk pagination/confirmation/replay, database rollback, duplicate concurrency, assignment authorization/target eligibility, HTTP authentication/CSRF and real-stack audit/realtime. Synthetic providers and disposable infrastructure are used; live provider/production acceptance remains separate.
