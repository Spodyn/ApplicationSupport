# Unified Support Inbox - Canonical Workflow Matrix

**Status:** FROZEN for v1  
**Related Jira:** USI-35, USI-36, USI-37, USI-39 and E09/E10/E13 freeze decisions

## 1. State diagram

```text
                         +-------------------------+
                         |                         |
                         v                         |
NEW --Claim----------> VERIFICATION --Ask SENT--> WAITING_FOR_CUSTOMER
 |                       |   |                       |        |
 |                       |   +--Resolve----------> RESOLVED  |
 |                       |                           ^        |
 |                       +--Admin unassign--> NEW    |        |
 |                                                   |        |
 +--Ignore (<2)--> PARTIALLY_IGNORED                 |        |
 |                   |       |                       |        |
 |                   |       +--Claim----------> VERIFICATION|
 |                   |                               |        |
 |                   +--Ignore (>=2)--> IGNORED      |        |
 |                                                   |        |
 +--Ignore (>=2)----------------------> IGNORED      |        |
                                                     |        |
ADMIN + force_resolve from any nonterminal ----------+        |
                                                              |
WAITING customer reply / timeout ---------------------------> NEW

RESOLVED / IGNORED + later customer message -> new linked NEW Case
```

## 2. Status invariants

| Status | Owner | Terminal | Normal support reply | Notes |
|---|---|---:|---:|---|
| `NEW` | none | no | no | Active unclaimed work |
| `PARTIALLY_IGNORED` | none | no | no | Ignore points below threshold |
| `VERIFICATION` | exactly one | no | current owner only | Active owned work |
| `WAITING_FOR_CUSTOMER` | none | no | no normal Reply | Waiting after Ask was accepted by provider |
| `IGNORED` | none | yes | no | Never reopened |
| `RESOLVED` | historical last owner or null | yes | no | Never reopened |

## 3. Action matrix

| Action | Actor / permission | Allowed from | Preconditions | Result | Owner after | Primary side effects |
|---|---|---|---|---|---|---|
| Claim | active USER/ADMIN | `NEW`, `PARTIALLY_IGNORED` | target remains unowned; actor is not lifetime ignore voter | `VERIFICATION` | actor | deactivate active partial ignore votes; clear stale snoozes; audit Claim; start/resume in-progress, pause unclaimed |
| Reply | current owner | `VERIFICATION` | actor has not ever voted Ignore in this Case | `VERIFICATION` | unchanged | durable SUPPORT Message + outbox; delivery async; first-response may complete on `SENT` |
| Ignore | eligible viewer | `NEW`, `PARTIALLY_IGNORED` | effective weight calculated by backend | partial or `IGNORED` | none | store immutable vote snapshot; update points; actor becomes lifetime-ineligible for Claim/Reply |
| Ask Customer | current owner | `VERIFICATION` | outgoing human support message required | remains `VERIFICATION` until provider `SENT`; then `WAITING_FOR_CUSTOMER` | owner until `SENT`, then null | set waiting deadline after `SENT`; waiting SLA pause if policy; delivery failure keeps ownership |
| Resolve | current owner | `VERIFICATION` | optional controlled resolution category | `RESOLVED` | preserves owner | end SLA timers; clear snoozes; audit resolution |
| Admin assign | `ADMIN + reassign_cases` | `NEW`, `PARTIALLY_IGNORED` | target active/eligible/not lifetime ignore voter | `VERIFICATION` | target | audit assignment; not counted as Claim metric |
| Admin reassign | `ADMIN + reassign_cases` | `VERIFICATION` | target active/eligible/not lifetime ignore voter | `VERIFICATION` | target | in-progress time continues; audit change |
| Admin unassign | `ADMIN + reassign_cases` | `VERIFICATION` | none | `NEW` | null | preserve elapsed SLA; audit; unclaimed resumes |
| Force resolve | `ADMIN + force_resolve` | any nonterminal | none | `RESOLVED` | previous owner if any, otherwise null | `forced=true`; admin actor only in audit; end timers/snoozes |
| Personal Snooze | any active viewer | any nonterminal | 5m-30d | status unchanged | unchanged | mark read for actor; private reminder; SLA unchanged |
| Mark read | current user | any visible Case | frontend actually displayed content | status unchanged | unchanged | advance only current user's stable read cursor |

## 4. Ignore vote detailed semantics

Threshold: `2`.

Effective weight:

- default 1;
- temporary 2 may be granted by `ADMIN + manage_users` with optional expiry;
- the weight on a vote is an immutable snapshot.

Duplicate command:

- if the actor already has an active vote, return idempotent success;
- do not create another vote or add points again.

Reset behavior:

- a successful Claim by another user deactivates active partial votes;
- a new customer message before threshold deactivates active partial votes;
- history remains;
- an old voter may vote again after reset;
- lifetime Claim/Reply prohibition remains forever for that Case.

## 5. Ask Customer lifecycle

### 5.1 Start

1. Current owner chooses Ask Customer in `VERIFICATION`.
2. The command durably creates the outgoing human support Message and provider-delivery work.
3. Case remains `VERIFICATION`; owner remains unchanged while the Message is queued/sending/retrying.
4. When provider returns successful acceptance and Message becomes `SENT`, the Case atomically becomes `WAITING_FOR_CUSTOMER`, owner becomes null and `waiting_until` is established.

This ordering prevents a Case from appearing to wait for a customer who never received the question.

### 5.2 Failure

- Transient provider failure -> retry same Message under common retry policy.
- Permanent provider failure -> Message `FAILED`; Case remains `VERIFICATION` with the original owner.

### 5.3 Customer reply

Customer inbound while waiting:

- stores/dedupes the customer Message;
- Case -> `NEW`;
- clears waiting deadline;
- marks Case unread for eligible users;
- ends personal snoozes;
- resumes applicable SLA timers with preserved accumulated business time.

### 5.4 Timeout

At `waiting_until`:

- atomically Case -> `NEW`;
- clear waiting deadline;
- write system/audit `WAITING_TIMEOUT`;
- resume applicable SLA timers with preserved accumulated time;
- do **not** send automatic follow-up in v1.

## 6. Terminal Case and linked successor

`RESOLVED` and `IGNORED` cannot reopen.

When the next customer message arrives for the same provider conversation identity:

1. persist/deduplicate inbound event;
2. create exactly one new `NEW` Case under concurrency protection;
3. set `new.related_case_id` to the immediately previous terminal Case;
4. route concurrent or immediately following customer messages into that same newly created Case.

The result is a chain of Cases rather than mutation of historic terminal outcomes.

## 7. Read/unread matrix

| Event | Marks unread? | For whom? |
|---|---:|---|
| New customer Message | yes | all active users who may see Case |
| Customer Message edit | yes | all eligible users |
| Customer Message delete | no new unread by itself | none |
| Human support outgoing | no | none |
| OOO/system customer-facing | no | none |
| Claim/reassign/status-only change | no | none |
| Audit/activity-only event | no | none |

Read acknowledgement affects only the current user.

## 8. SLA state interaction

| Workflow situation | First-response | Unclaimed | In-progress |
|---|---|---|---|
| `NEW` / `PARTIALLY_IGNORED` | running until completed | running | paused |
| `VERIFICATION` | running until first human `SENT` | paused | running |
| `WAITING_FOR_CUSTOMER` | if not already completed, policy semantics remain; Ask `SENT` can complete it | active-unowned semantics preserved but waiting is not counted as active unclaimed work | paused; waiting pause policy applies |
| `RESOLVED` / `IGNORED` | stopped | stopped | stopped |

Timers preserve accumulated business time across pause/resume. Reassign never resets in-progress time.

## 9. Concurrency requirements

Implementation must prove through tests that:

- two simultaneous Claims cannot produce two owners;
- two simultaneous terminal transitions cannot both win inconsistently;
- repeated commands with the same idempotency key create one business effect;
- duplicate provider events create one inbound business effect;
- simultaneous Ignore votes produce a correct threshold transition exactly once;
- a post-terminal burst creates exactly one linked successor Case;
- scheduler workers use safe claiming (for example `FOR UPDATE SKIP LOCKED`) so a deadline/event is not processed concurrently by multiple workers.

The exact locking/query implementation is a delegated technical choice so long as these invariants hold.
