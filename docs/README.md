# Unified Support Inbox - canonical documentation

**Status:** Frozen v1 contract  
**Decision freeze:** complete  
**Primary Jira epic:** USI-5 / E00 - Product Contract & Architecture Freeze

This directory consolidates the approved product and architecture contract for Unified Support Inbox (USI). It is intended to remove ambiguity for developers, reviewers and autonomous agents.

## Source-of-truth precedence

When two sources appear to disagree, use this order:

1. The current Jira ticket and any later `FINAL DECISION FREEZE` / `CONTRACT OVERRIDE` comment on that ticket.
2. The frozen product decisions in E00 and the approved parent-epic pre-flight decisions for E01-E25.
3. `PRODUCT_CONTRACT.md` and `decision-registry.yaml` in this directory.
4. `PRODUCT_SPEC.md`, `WORKFLOW_MATRIX.md`, `INTEGRATIONS.md`, `SECURITY.md`, `ARCHITECTURE.md` and `OPERATIONS.md`.
5. Older domain notes, mock documentation and implementation details, provided they do not conflict with the items above.

An older Jira description or historical `USER_DECISION_REQUIRED` comment does not override a later resolved decision.

## Documents

- **PRODUCT_CONTRACT.md** - concise normative rules that must not be changed by implementation convenience.
- **PRODUCT_SPEC.md** - human-readable description of how the whole product works, feature by feature.
- **WORKFLOW_MATRIX.md** - canonical Case state machine, action guards and side effects.
- **ARCHITECTURE.md** - technical architecture and implementation boundaries.
- **INTEGRATIONS.md** - Slack, Microsoft Teams and Telegram behavior.
- **SECURITY.md** - authentication, authorization, content security, secrets, file safety and CI security.
- **OPERATIONS.md** - observability, deployment, backups, disaster recovery, performance and release gates.
- **DECISION_REGISTRY.md** - indexed, readable decision catalog.
- **decision-registry.yaml** - machine-readable frozen decisions for agents/orchestrators.

## Scope

USI v1 is a single-tenant-per-deployment support inbox that unifies Slack, Microsoft Teams and Telegram. E-mail support and AI functionality are deliberately outside v1.

## Agent rule

Routine technical choices are delegated. Agents must not stop for naming, package layout, ordinary indexes, equivalent locking strategies, retry details, test structure, local refactors or Git conflict resolution when those choices remain within the frozen contract. Human approval is required only for a real contract change such as v1 scope expansion, a fundamental workflow change, a new role or permission, a new security boundary, destructive data semantics outside the contract, a paid third-party service, production credentials/actions, pricing/customer commitments, or a new legal/compliance decision.
