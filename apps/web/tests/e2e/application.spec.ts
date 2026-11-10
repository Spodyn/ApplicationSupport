import { expect, test, type Page } from "@playwright/test"

const localOrigin = "http://127.0.0.1:3100"

const authenticatedSession = {
  id: "018f0000-0000-7000-8000-000000000064",
  email: "anna.kowalska@firma.pl",
  displayName: "Anna Kowalska",
  role: "ADMIN",
  createdAt: "2024-01-12T08:00:00.000Z",
  effectivePermissions: ["manage_users"],
}

const caseId = "018f0000-0000-7000-8000-000000000201"
const inboundMessageId = "018f0000-0000-7000-8000-000000000202"
const caseListItem = {
  id: caseId, reference: "CASE-00000002", status: "NEW", customerId: "customer",
  customerName: "Slack Test Customer", channelId: "channel", channelName: "new-channel",
  externalChannelId: "C0C6N61M4HZ", provider: "SLACK", ownerUserId: null,
  ownerDisplayName: null, lastMessageId: inboundMessageId, lastMessagePreview: "Hello from Slack - USI test 1",
  unreadForCurrentUser: true, snoozedUntil: null, slaState: "ON_TRACK", slaDueAt: null,
  ignoreScore: 0, waitingUntil: null, createdAt: "2026-10-05T10:00:00Z",
  updatedAt: "2026-10-05T10:00:00Z", lastActivityAt: "2026-10-05T10:00:00Z",
}
const inboundMessage = {
  id: inboundMessageId, kind: "CUSTOMER", body: "Hello from Slack - USI test 1", bodyFormat: "PLAIN_TEXT",
  inbound: true, deliveryStatus: null, providerCreatedAt: caseListItem.createdAt,
  createdAt: caseListItem.createdAt, editedAt: null, deletedAt: null, authorName: "Customer",
  attachments: [] as Array<{
    id: string
    fileName: string
    sizeBytes: number
    contentType: string | null
    detectedContentType: string | null
    scanStatus: string
  }>,
}

async function preparePage(page: Page, initiallyAuthenticated = true, options: { incomingBody?: string; customerName?: string; sendGate?: Promise<void> } = {}) {
  const incomingMessage = { ...inboundMessage, body: options.incomingBody ?? inboundMessage.body }
  const listItem = { ...caseListItem, customerName: options.customerName ?? caseListItem.customerName }
  const externalRequests: string[] = []
  let authenticated = initiallyAuthenticated
  let claimed = false
  let read = false
  const supportMessages: Array<Omit<typeof inboundMessage, "deliveryStatus"> & { deliveryStatus: string | null; retryAllowed?: boolean }> = []
  const sends: string[] = []
  const uploads: string[] = []
  const retries: string[] = []
  const historyBefore: Array<string | null> = []
  await page.context().addCookies([{ name: "XSRF-TOKEN", value: "e2e-csrf", url: localOrigin }])

  await page.route("**/*", async (route) => {
    const request = route.request()
    const requestUrl = new URL(request.url())
    const isHttp = requestUrl.protocol === "http:" || requestUrl.protocol === "https:"

    if (isHttp && requestUrl.origin !== localOrigin) {
      externalRequests.push(requestUrl.href)
      await route.abort("blockedbyclient")
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/me") {
      if (authenticated) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(authenticatedSession),
        })
      } else {
        await route.fulfill({
          status: 401,
          contentType: "application/problem+json",
          headers: {
            "set-cookie": "XSRF-TOKEN=e2e-csrf; Path=/; SameSite=Lax",
          },
          body: JSON.stringify({
            code: "AUTHENTICATION_REQUIRED",
            title: "Authentication required",
            status: 401,
            detail: "Authentication is required to access this resource.",
            correlationId: "e2e-correlation",
          }),
        })
      }
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/login" && request.method() === "POST") {
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      expect(requestUrl.search).toBe("")
      expect(request.postDataJSON()).toEqual({ email: "anna.kowalska@firma.pl", password: "test-only-credential" })
      authenticated = true
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(authenticatedSession),
      })
      return
    }

    if (requestUrl.pathname === "/api/v1/admin/users/current" && request.method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          id: authenticatedSession.id,
          fullName: authenticatedSession.displayName,
          email: authenticatedSession.email,
          role: authenticatedSession.role,
          active: true,
          validFrom: authenticatedSession.createdAt,
          validUntil: null,
          lastLoginAt: authenticatedSession.createdAt,
          presence: "online",
          activeAssignedCases: 0,
          permissions: authenticatedSession.effectivePermissions,
        }),
      })
      return
    }

    if (requestUrl.pathname === "/api/v1/admin/users" && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/logout" && request.method() === "POST") {
      authenticated = false
      await route.fulfill({ status: 204 })
      return
    }

    if (requestUrl.pathname === "/api/v1/cases" && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        items: [{ ...listItem, status: claimed ? "VERIFICATION" : "NEW",
          ownerUserId: claimed ? authenticatedSession.id : null,
          ownerDisplayName: claimed ? authenticatedSession.displayName : null,
          unreadForCurrentUser: !read }], nextCursor: null,
      }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}` && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        id: caseId, reference: listItem.reference, status: claimed ? "VERIFICATION" : "NEW",
        customer: { id: "customer", name: listItem.customerName, externalRef: "slack-test" },
        owner: { id: claimed ? authenticatedSession.id : null, displayName: claimed ? authenticatedSession.displayName : null },
        channel: { id: "channel", name: "new-channel", externalChannelId: "C0C6N61M4HZ", groupingStrategy: "SLACK_ROOT_THREAD" },
        integration: { id: "integration", provider: "SLACK", displayName: "TestApp", workspaceExternalId: "team", workspaceName: "TestApp" },
        relatedCase: { id: null, reference: null }, personalState: { lastReadMessageId: read ? inboundMessageId : null, lastReadAt: null, snoozedUntil: null },
        sla: null, ignoreScore: 0, availableActions: claimed ? ["REPLY", "MARK_READ"] : ["CLAIM", "MARK_READ"],
        claimedAt: null, waitingUntil: null, resolvedAt: null, ignoredAt: null, resolutionCategory: null,
        createdAt: listItem.createdAt, updatedAt: listItem.updatedAt, lastActivityAt: listItem.lastActivityAt, version: claimed ? 2 : 1,
      }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/messages` && request.method() === "GET") {
      const before = requestUrl.searchParams.get("before")
      historyBefore.push(before)
      if (before === "older-cursor") {
        const olderMessage = {
          ...incomingMessage,
          id: "018f0000-0000-7000-8000-000000000199",
          body: "Older persisted message",
          providerCreatedAt: "2026-10-05T09:00:00Z",
          createdAt: "2026-10-05T09:00:00Z",
        }
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ items: [incomingMessage, olderMessage], nextCursor: null }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          items: [...supportMessages].reverse().concat(incomingMessage),
          nextCursor: "older-cursor",
        }),
      })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/read-position` && request.method() === "PUT") {
      read = true
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ caseId, messageId: inboundMessageId, readAt: new Date().toISOString() }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/claim` && request.method() === "POST") {
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/)
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      claimed = true
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ caseId, status: "VERIFICATION", ownerUserId: authenticatedSession.id, version: 2 }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/attachments` && request.method() === "POST") {
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      expect(request.headers()["content-type"]).toContain("multipart/form-data")
      uploads.push("evidence.txt")
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          attachmentId: "018f0000-0000-7000-8000-000000000205",
          filename: "evidence.txt",
          contentType: "text/plain",
          sizeBytes: 4,
          scanStatus: "CLEAN",
          scanError: null,
        }),
      })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/attachments/018f0000-0000-7000-8000-000000000205` && request.method() === "DELETE") {
      await route.fulfill({ status: 204 })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/messages` && request.method() === "POST") {
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/)
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      const body = request.postDataJSON() as { body: string; attachmentIds?: string[] }
      sends.push(body.body)
      if (options.sendGate) await options.sendGate
      const id = `018f0000-0000-7000-8000-00000000020${3 + supportMessages.length}`
      supportMessages.push({
        ...incomingMessage,
        id,
        kind: "SUPPORT",
        inbound: false,
        body: body.body,
        authorName: authenticatedSession.displayName,
        deliveryStatus: "QUEUED",
        attachments: body.attachmentIds?.map((attachmentId) => ({
          id: attachmentId,
          fileName: "evidence.txt",
          sizeBytes: 4,
          contentType: "text/plain",
          detectedContentType: "text/plain",
          scanStatus: "CLEAN",
        })) ?? [],
      })
      await route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify({ messageId: id, deliveryStatus: "QUEUED" }) })
      return
    }
    const retryMatch = requestUrl.pathname.match(/^\/api\/v1\/messages\/([^/]+)\/retry$/)
    if (retryMatch && request.method() === "POST") {
      const messageId = retryMatch[1]
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/)
      const message = supportMessages.find((candidate) => candidate.id === messageId)
      if (message) {
        message.deliveryStatus = "QUEUED"
        message.retryAllowed = false
      }
      retries.push(messageId)
      await route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({ messageId, deliveryStatus: "QUEUED" }),
      })
      return
    }
    await route.continue()
  })

  return { externalRequests, sends, uploads, retries, historyBefore, supportMessages }
}

async function prepareSettingsApi(page: Page, options: { forbidden?: boolean } = {}) {
  const writes: Array<{ method: string; path: string; body?: unknown }> = []
  let integration = {
    id: "018f0000-0000-7000-8000-000000000301",
    provider: "SLACK",
    displayName: "Acme Slack",
    status: "ENABLED",
    health: "UNKNOWN",
    workspaceExternalId: "T-ACME",
    workspaceName: "Acme Workspace",
    secretConfigured: true,
    secretRef: "sandbox-secret-must-not-be-rendered",
    lastEventAt: null,
    lastErrorCode: null,
    createdAt: "2026-10-01T08:00:00Z",
    updatedAt: "2026-10-01T08:00:00Z",
  }

  await page.route("**/api/v1/admin/**", async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (path === "/api/v1/admin/settings" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        general: { organizationName: "Test organization", interfaceLanguage: "pl", compactMode: false },
        rolePermissions: [{ role: "admin", permissions: ["manage_users", "manage_integrations"] }, { role: "user", permissions: [] }],
        warningBeforeDeadlineMinutes: 15, businessHoursOnly: true,
      }) })
      return
    }
    if (["/api/v1/admin/customers", "/api/v1/admin/notifications/deliveries"].includes(path) && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
      return
    }
    if (path === "/api/v1/admin/out-of-office/preview" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "Poza biurem", timezone: "Europe/Warsaw" }) })
      return
    }
    if (path === "/api/v1/admin/integrations" && method === "GET") {
      if (options.forbidden) {
        await route.fulfill({
          status: 403,
          contentType: "application/problem+json",
          body: JSON.stringify({ code: "ACCESS_DENIED", title: "Access denied", status: 403 }),
        })
        return
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([integration]) })
      return
    }
    if (path.endsWith(`/integrations/${integration.id}/status`) && method === "PATCH") {
      const body = request.postDataJSON() as { status: "ENABLED" | "DISABLED" }
      writes.push({ method, path, body })
      integration = { ...integration, status: body.status, updatedAt: "2026-10-01T08:05:00Z" }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(integration) })
      return
    }
    if (path.endsWith(`/integrations/${integration.id}/test`) && method === "POST") {
      writes.push({ method, path })
      integration = { ...integration, health: "HEALTHY", updatedAt: "2026-10-01T08:06:00Z" }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ integration, outcome: "SUCCESS", errorCode: null }) })
      return
    }
    if (path === "/api/v1/admin/channels" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
      return
    }
    if (path === "/api/v1/admin/business-hours" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        id: "018f0000-0000-7000-8000-000000000302", timezone: "Europe/Warsaw", active: true,
        intervals: [], updatedBy: authenticatedSession.id, updatedAt: "2026-10-01T08:00:00Z",
      }) })
      return
    }
    if (path === "/api/v1/admin/notifications/destinations" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
      return
    }
    if (path === "/api/v1/admin/notifications/rules" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
      return
    }
    if (path === "/api/v1/admin/out-of-office" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        enabled: false, template: "Poza biurem", sendOncePerClosure: true, version: 1,
        updatedAt: "2026-10-01T08:00:00Z", updatedBy: authenticatedSession.id,
      }) })
      return
    }
    if (path === "/api/v1/admin/sla-policy" && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        id: "018f0000-0000-7000-8000-000000000303", name: "Default", firstResponseMinutes: 30,
        unclaimedWarningMinutes: 10, unclaimedBreachMinutes: 30, inProgressWarningMinutes: 60,
        inProgressBreachMinutes: 120, pauseWaiting: true, version: 1, updatedAt: "2026-10-01T08:00:00Z",
      }) })
      return
    }
    await route.fallback()
  })

  return { writes }
}

const smokeRoutes = [
  { path: "/cases", heading: "Czaty" },
  { path: "/statistics", heading: "Statystyki" },
  { path: "/users", heading: "Użytkownicy" },
  { path: "/settings", heading: "Ustawienia" },
] as const

for (const route of smokeRoutes) {
  test(`${route.path} ładuje główny widok`, async ({ page }) => {
    const { externalRequests } = await preparePage(page)

    await page.goto(route.path)

    await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible()
    expect(externalRequests).toEqual([])
  })
}

test("niezalogowany użytkownik trafia na login i może utworzyć sesję", async ({ page }) => {
  const { externalRequests } = await preparePage(page, false)

  await page.goto("/cases")
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole("heading", { name: "Zaloguj się" })).toBeVisible()

  await page.getByLabel("E-mail").fill("anna.kowalska@firma.pl")
  await page.getByLabel("Hasło").fill("test-only-credential")
  await page.getByRole("button", { name: "Zaloguj się" }).click()

  await expect(page).toHaveURL(/\/cases$/)
  await expect(page.getByRole("heading", { level: 1, name: "Czaty" })).toBeVisible()
  expect(externalRequests).toEqual([])
})

test("real API Case opens, is marked read, claimed, and receives one persisted support reply", async ({ page }) => {
  const { externalRequests, sends } = await preparePage(page)
  await page.goto("/cases")
  const chat = page.getByRole("button", { name: /Slack Test Customer/ })
  await expect(chat).toContainText("Hello from Slack - USI test 1")
  await chat.click()
  await expect(chat.getByLabel("Nieodczytane")).toHaveCount(0)
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation.getByText("Hello from Slack - USI test 1")).toBeVisible()
  await conversation.getByRole("button", { name: "Przejmij" }).click()
  await expect(conversation.getByText("Anna Kowalska")).toBeVisible()
  await conversation.getByLabel("Treść odpowiedzi").fill("USI test reply")
  await conversation.getByRole("button", { name: "Wyślij" }).click()
  await expect(conversation.getByText("USI test reply")).toBeVisible()
  expect(sends).toEqual(["USI test reply"])
  expect(externalRequests).toEqual([])
})

test("conversation lazy-loads older history without duplicating overlapping messages", async ({ page }) => {
  const { historyBefore } = await preparePage(page)
  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await conversation.getByRole("button", { name: "Wczytaj starsze wiadomości" }).click()
  await expect(conversation.getByText("Older persisted message")).toBeVisible()
  await expect(conversation.getByText("Hello from Slack - USI test 1")).toHaveCount(1)
  expect(historyBefore).toContain("older-cursor")
})

test("current owner uploads an attachment and sends it with the persisted message", async ({ page }) => {
  const { uploads } = await preparePage(page)
  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await conversation.getByRole("button", { name: "Przejmij" }).click()

  await conversation.locator('input[type="file"]').setInputFiles({
    name: "evidence.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("test"),
  })
  await expect(conversation.getByText("evidence.txt")).toBeVisible()
  await conversation.getByLabel("Treść odpowiedzi").fill("Reply with evidence")
  await conversation.getByRole("button", { name: "Wyślij" }).click()

  await expect(conversation.getByText("Reply with evidence")).toBeVisible()
  await expect(conversation.getByRole("link", { name: /evidence\.txt/ })).toHaveAttribute(
    "href",
    `/api/v1/cases/${caseId}/attachments/018f0000-0000-7000-8000-000000000205`,
  )
  expect(uploads).toEqual(["evidence.txt"])
})

test("changing attachments after a failed send uses a fresh idempotency key", async ({ page }) => {
  await preparePage(page)
  const sendKeys: string[] = []
  let rejectFirstSend = true
  await page.route(`**/api/v1/cases/${caseId}/messages`, async (route) => {
    const request = route.request()
    if (request.method() !== "POST") {
      await route.fallback()
      return
    }
    sendKeys.push(request.headers()["idempotency-key"] ?? "")
    if (!rejectFirstSend) {
      await route.fallback()
      return
    }
    rejectFirstSend = false
    await route.fulfill({
      status: 503,
      contentType: "application/problem+json",
      body: JSON.stringify({
        code: "APPLICATION_FAILURE",
        title: "Unavailable",
        status: 503,
        detail: "Message send failed",
        correlationId: "e2e-send-retry",
      }),
    })
  })

  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await conversation.getByRole("button", { name: "Przejmij" }).click()
  await conversation.getByLabel("Treść odpowiedzi").fill("Retry with evidence")
  await conversation.getByRole("button", { name: "Wyślij" }).click()
  await expect(conversation.getByText("Nie udało się wykonać żądania. Spróbuj ponownie.")).toBeVisible()

  await conversation.locator('input[type="file"]').setInputFiles({
    name: "evidence.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("test"),
  })
  await conversation.getByRole("button", { name: "Wyślij" }).click()
  // The editor still contains this text while the asynchronous upload/send runs.
  // Wait for the persisted conversation message, not the textarea's text node.
  await expect(conversation.locator("p").filter({ hasText: "Retry with evidence" })).toBeVisible()

  await expect.poll(() => sendKeys.length).toBe(2)
  expect(sendKeys[0]).toMatch(/^[0-9a-f-]{36}$/)
  expect(sendKeys[1]).toMatch(/^[0-9a-f-]{36}$/)
  expect(sendKeys[1]).not.toBe(sendKeys[0])
})

test("failed outbound message can be retried without creating duplicate content", async ({ page }) => {
  const { retries, supportMessages } = await preparePage(page)
  const failedId = "018f0000-0000-7000-8000-000000000204"
  supportMessages.push({
    ...inboundMessage,
    id: failedId,
    kind: "SUPPORT",
    inbound: false,
    body: "Retry me",
    authorName: authenticatedSession.displayName,
    deliveryStatus: "FAILED", retryAllowed: true,
    attachments: [],
  })

  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await conversation.getByRole("button", { name: "Przejmij" }).click()
  await expect(conversation.getByText("Retry me")).toBeVisible()
  await conversation.getByRole("button", { name: "Ponów" }).click()
  await expect(conversation.getByRole("button", { name: "Ponów" })).toHaveCount(0)
  await expect(conversation.getByText(/W kolejce/)).toBeVisible()
  expect(retries).toEqual([failedId])
})

test("message API failure renders the conversation error state", async ({ page }) => {
  await preparePage(page)
  await page.route("**/api/v1/cases/*/messages*", async (route) => {
    await route.fulfill({
      status: 503,
      contentType: "application/problem+json",
      body: JSON.stringify({
        code: "APPLICATION_FAILURE",
        title: "Unavailable",
        status: 503,
        detail: "Messages unavailable",
        correlationId: "e2e-message-error",
      }),
    })
  })
  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation.getByText("Nie udało się wczytać wiadomości.")).toBeVisible()
})

test("empty persisted conversation renders an empty state", async ({ page }) => {
  await preparePage(page)
  await page.route("**/api/v1/cases/*/messages*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [], nextCursor: null }) })
  })
  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation.getByText("Brak wiadomości.")).toBeVisible()
  await expect(conversation.getByText("Nie udało się wczytać wiadomości.")).toHaveCount(0)
})

test("API user directory opens a fresh account editor for an authorized administrator", async ({ page }) => {
  const { externalRequests } = await preparePage(page)
  // Exercise the real editor under an administrator allowed to manage users.
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        ...authenticatedSession,
        role: "ADMIN",
        effectivePermissions: ["manage_users"],
      }),
    })
  })
  await page.route("**/api/v1/admin/users**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [{
          id: authenticatedSession.id,
          email: authenticatedSession.email,
          fullName: authenticatedSession.displayName,
          role: "ADMIN",
          active: true,
          presence: "online",
          validFrom: null,
          validUntil: null,
          lastLoginAt: null,
          activeAssignedCases: 0,
          createdAt: authenticatedSession.createdAt,
        }],
        nextCursor: null,
      }),
    })
  })
  await page.goto("/users")
  const addUserButton = page.getByRole("banner").getByRole("button", { name: "Dodaj użytkownika" })

  await addUserButton.click()
  const dialog = page.getByRole("dialog", { name: "Dodaj użytkownika" })
  await expect(dialog).toBeVisible()

  await dialog.getByLabel("Imię i nazwisko").fill("Test tymczasowy")
  await dialog.getByLabel("E-mail").fill("test@example.invalid")
  await dialog.getByRole("button", { name: "Anuluj" }).click()

  await addUserButton.click()
  const reopenedDialog = page.getByRole("dialog", { name: "Dodaj użytkownika" })
  await expect(reopenedDialog.getByLabel("Imię i nazwisko")).toHaveValue("")
  await expect(reopenedDialog.getByLabel("E-mail")).toHaveValue("")
  expect(externalRequests).toEqual([])
})

test("Settings używa realnego common API do testowania i zmiany statusu integracji", async ({ page }) => {
  const { externalRequests } = await preparePage(page)
  const { writes } = await prepareSettingsApi(page)

  await page.goto("/settings")
  await expect(page.getByText("Konfiguracja chroniona")).toBeVisible()
  await page.getByRole("tab", { name: "Integracje" }).click()

  await expect(page.getByText("Acme Workspace").first()).toBeVisible()
  await expect(page.getByText("sandbox-secret-must-not-be-rendered")).toHaveCount(0)
  await expect(page.getByRole("button", { name: "Konfiguruj", exact: true })).toBeVisible()

  await page.getByRole("button", { name: "Testuj" }).click()
  await expect.poll(() => writes.some((write) => write.method === "POST" && write.path.endsWith("/test"))).toBe(true)

  await page.getByRole("button", { name: "Wyłącz" }).click()
  await page.getByRole("alertdialog", { name: "Wyłączyć integrację?" }).getByRole("button", { name: "Wyłącz" }).click()
  await expect(page.getByRole("button", { name: "Włącz" })).toBeVisible()
  expect(writes).toContainEqual(expect.objectContaining({ method: "PATCH", body: { status: "DISABLED" } }))

  await page.getByRole("button", { name: "Włącz" }).click()
  await expect(page.getByRole("button", { name: "Wyłącz" })).toBeVisible()
  expect(writes).toContainEqual(expect.objectContaining({ method: "PATCH", body: { status: "ENABLED" } }))
  expect(externalRequests).toEqual([])
})

test("Settings pokazuje stan błędu, gdy backend odmawia dostępu do konfiguracji integracji", async ({ page }) => {
  await preparePage(page)
  await prepareSettingsApi(page, { forbidden: true })

  await page.goto("/settings")

  await expect(page.getByText("Wystąpił błąd")).toBeVisible()
  await expect(page.getByRole("button", { name: "Spróbuj ponownie" })).toBeVisible()
})

test("mobilny widok czatów przechodzi z listy do rozmowy i wraca", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { externalRequests } = await preparePage(page)
  await page.goto("/cases")

  const chatList = page.getByRole("region", { name: "Lista czatów" })
  await expect(chatList).toBeVisible()
  await page.getByRole("button", { name: /Slack Test Customer/ }).click()

  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation).toBeVisible()
  await conversation.getByRole("button", { name: "Wróć do listy" }).click()

  await expect(chatList).toBeVisible()
  expect(externalRequests).toEqual([])
})

test("language switches immediately, survives reload and leaves customer and provider content untouched", async ({ page }) => {
  const errors: string[] = []
  page.on("pageerror", (error) => errors.push(error.message))
  await preparePage(page, true, { customerName: "Nowe", incomingBody: "Rozwiązane — treść klienta" })
  await prepareSettingsApi(page)
  await page.goto("/cases")
  await page.getByRole("button", { name: /Otwórz menu profilu/ }).click()
  await page.getByLabel("Język interfejsu").selectOption("en")
  await page.keyboard.press("Escape")
  await expect(page.getByRole("heading", { name: "Chats", exact: true })).toBeVisible()
  await expect(page.getByRole("region", { name: "Conversation Nowe" })).toBeVisible()
  await expect(page.getByText("Rozwiązane — treść klienta", { exact: true })).toBeVisible()
  await expect(page.getByRole("button", { name: /Nowe/ })).toBeVisible()
  await page.reload()
  await expect(page.locator("html")).toHaveAttribute("lang", "en")
  await expect(page.getByRole("heading", { name: "Chats", exact: true })).toBeVisible()
  await page.getByRole("link", { name: "Settings", exact: true }).click()
  await expect(page.getByRole("heading", { name: "Settings", exact: true })).toBeVisible()
  await expect(page.getByRole("tab", { name: "Integrations", exact: true })).toBeVisible()
  await page.getByLabel("Interface language").selectOption("pl")
  await expect(page.getByRole("heading", { name: "Ustawienia", exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test("public authentication language is persisted and server renders the chosen locale", async ({ page }) => {
  await preparePage(page, false)
  await page.goto("/login")
  await page.getByLabel("Język interfejsu").selectOption("en")
  await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByLabel("Password", { exact: true })).toBeVisible()
  const html = await (await page.request.get("/login")).text()
  expect(html).toContain('lang="en"')
  expect(html).toContain("Sign in")
  await page.getByRole("link", { name: "Set password", exact: true }).click()
  await expect(page.getByLabel("New password", { exact: true })).toBeVisible()
})

test("composer Enter sends once with attachments, Shift+Enter preserves newlines, IME and blank Enter do not send", async ({ page }) => {
  let release = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  const state = await preparePage(page, true, { sendGate: gate })
  await page.goto("/cases")
  const panel = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await panel.getByRole("button", { name: "Przejmij", exact: true }).click()
  const composer = panel.getByLabel("Treść odpowiedzi")
  await expect(composer).toBeEnabled()
  await composer.fill("   ")
  await composer.press("Enter")
  expect(state.sends).toEqual([])
  await composer.fill("Pierwszy wiersz")
  await composer.press("Shift+Enter")
  await composer.press("End")
  await composer.type("Drugi wiersz")
  await expect(composer).toHaveValue("Pierwszy wiersz\nDrugi wiersz")
  await composer.dispatchEvent("compositionstart")
  await composer.dispatchEvent("keydown", { key: "Enter", code: "Enter", isComposing: true, bubbles: true })
  await composer.dispatchEvent("compositionend")
  await composer.dispatchEvent("keydown", { key: "Enter", code: "Enter", keyCode: 229, bubbles: true })
  expect(state.sends).toEqual([])
  await panel.locator('input[type="file"]').setInputFiles({name:"evidence.txt",mimeType:"text/plain",buffer:Buffer.from("test")})
  await composer.press("Enter")
  await expect.poll(() => state.sends.length).toBe(1)
  await composer.dispatchEvent("keydown", { key: "Enter", bubbles: true })
  await composer.dispatchEvent("keydown", { key: "Enter", repeat: true, bubbles: true })
  await expect(composer).toBeDisabled()
  expect(state.sends).toEqual(["Pierwszy wiersz\nDrugi wiersz"])
  release()
  await expect(composer).toHaveValue("")
  await expect(panel.getByRole("link", {name:/evidence.txt/})).toBeVisible()
  expect(state.uploads).toEqual(["evidence.txt"])
  expect(state.sends).toHaveLength(1)
})

for (const width of [1440, 820, 390]) {
  test(`long incoming and outgoing messages wrap without page overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const link = `https://example.test/${"identifier".repeat(140)}`
    const long = `${"A".repeat(5000)}\nhttps://example.test/${"identifier".repeat(400)}\n${"Natural paragraph with spaces. ".repeat(120)}\n[${link}](${link})\nLast line`
    await preparePage(page, true, { incomingBody: long })
    await page.goto("/cases")
    await page.getByRole("button", {name:/Slack Test Customer/}).click()
    const panel = page.getByRole("region", {name:"Rozmowa Slack Test Customer"})
    await panel.getByRole("button", {name:"Przejmij",exact:true}).click()
    await panel.getByLabel("Treść odpowiedzi").fill(long)
    await panel.getByRole("button", {name:"Wyślij",exact:true}).click()
    await expect(panel.locator('[data-message-kind="support"]')).toHaveCount(1)
    await panel.getByLabel("Treść odpowiedzi").fill("Second consecutive message\nWith another line")
    await panel.getByLabel("Treść odpowiedzi").press("Enter")
    await expect(panel.locator('[data-message-kind="support"]')).toHaveCount(2)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    for (const kind of ["customer", "support"]) {
      const text = panel.locator(`[data-message-kind="${kind}"] p`).first()
      await expect(text).toHaveText(long.replace(`[${link}](${link})`, link))
      expect(await text.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)).toBe(true)
      expect(await text.evaluate((node) => getComputedStyle(node).whiteSpace)).toBe("pre-wrap")
      await expect(text.getByRole("link")).toHaveAttribute("href", link)
    }
    const bubbles = await panel.locator('[data-message-kind="support"]').evaluateAll((nodes) => nodes.map((node) => ({top:node.getBoundingClientRect().top,bottom:node.getBoundingClientRect().bottom})))
    expect(bubbles[1].top).toBeGreaterThanOrEqual(bubbles[0].bottom)
  })
}

test("Case filters omit customer/channel, retain SLA filtering and buttons show hover and focus feedback", async ({ page }) => {
  await preparePage(page)
  const queries: string[] = []
  page.on("request", (request) => { const url = new URL(request.url()); if(url.pathname === "/api/v1/cases") queries.push(url.search) })
  await page.goto("/cases")
  const sla = page.getByRole("button", {name:"SLA",exact:true})
  await expect(sla.locator("svg")).toHaveCount(0)
  await sla.click()
  await expect.poll(() => queries.some((query) => query.includes("slaAttention=true"))).toBe(true)
  await page.getByRole("button", {name:"Filtry",exact:true}).click()
  await expect(page.getByLabel("Klient", {exact:true})).toHaveCount(0)
  await expect(page.getByLabel("Kanał", {exact:true})).toHaveCount(0)
  await expect(page.getByLabel("Dostawca", {exact:true})).toBeVisible()
  await page.keyboard.press("Escape")
  await page.getByRole("button", {name:"Filtry",exact:true}).click()
  const button = page.getByRole("region", {name:"Rozmowa Slack Test Customer"}).getByRole("button", {name:"Przejmij",exact:true})
  const normal = await button.evaluate((node) => getComputedStyle(node).filter)
  await button.hover()
  await expect.poll(() => button.evaluate((node) => getComputedStyle(node).filter)).not.toBe(normal)
  await button.focus()
  expect(await button.evaluate((node) => getComputedStyle(node).outlineStyle)).not.toBe("none")
})

test("bulk selection spans pages, distinguishes all matching, confirms and reports each real outcome", async ({ page }) => {
  await preparePage(page)
  const ids = [caseId, "018f0000-0000-7000-8000-000000000211", "018f0000-0000-7000-8000-000000000212"]
  const items = ids.map((id,index) => ({...caseListItem,id,reference:`CASE-0000000${index+2}`}))
  await page.route("**/api/v1/cases?**", async (route) => {
    const url = new URL(route.request().url())
    if(url.pathname !== "/api/v1/cases") return route.fallback()
    await route.fulfill({json:{items:url.searchParams.has("cursor")?items.slice(1):items.slice(0,1),nextCursor:url.searchParams.has("cursor")?null:"page-2"}})
  })
  const writes: Array<{body:unknown;key:string}> = []
  let release = () => {}
  const gate = new Promise<void>((resolve) => {release=resolve})
  await page.route("**/api/v1/cases/bulk", async (route) => {
    writes.push({body:route.request().postDataJSON(),key:route.request().headers()["idempotency-key"]})
    await gate
    await route.fulfill({json:{items:ids.map((id,index)=>({caseId:id,status:["SUCCEEDED","SKIPPED","FAILED"][index],code:["OK","ACTION_UNAVAILABLE","APPLICATION_FAILURE"][index]}))}})
  })
  await page.goto("/cases")
  await page.getByRole("button", {name:/Zaznacz widoczne/}).click()
  const toolbar = page.getByLabel("Operacje zbiorcze",{exact:true})
  await expect(toolbar).toContainText("Zaznaczono: 1")
  await expect(toolbar.getByRole("button",{name:"Przypisz",exact:true})).toHaveCount(0)
  await page.getByRole("button",{name:"Wczytaj więcej",exact:true}).click()
  await expect(page.getByLabel("Zaznacz CASE-00000004",{exact:true})).toBeVisible()
  await page.getByLabel("Zaznacz CASE-00000003",{exact:true}).focus()
  await page.keyboard.press("Space")
  await expect(toolbar).toContainText("Zaznaczono: 2")
  await toolbar.getByRole("button",{name:"Wyczyść zaznaczenie"}).click()
  await page.getByRole("button",{name:"Zaznacz wszystkie pasujące (do 1000)",exact:true}).click()
  await expect(toolbar).toContainText("Zaznaczono: 3")
  await toolbar.getByRole("button",{name:"Rozwiąż",exact:true}).click()
  const dialog = page.getByRole("dialog")
  await expect(dialog).toContainText("Zaznaczone sprawy: 3")
  await dialog.getByRole("button",{name:"Anuluj",exact:true}).click()
  expect(writes).toEqual([])
  await toolbar.getByRole("button",{name:"Rozwiąż",exact:true}).click()
  await dialog.getByRole("button",{name:"Zatwierdź",exact:true}).click()
  await expect.poll(()=>writes.length).toBe(1)
  await expect(dialog.getByRole("button",{name:"Zapisywanie…",exact:true})).toBeDisabled()
  release()
  await expect(page.getByRole("status").filter({hasText:"Wynik:"})).toContainText("wykonano: 1; pominięto: 1; błędy: 1")
  expect(writes).toHaveLength(1)
  expect(writes[0].body).toEqual({caseIds:ids,action:"RESOLVE",targetUserId:null})
  await expect(page.getByLabel("Zaznacz CASE-00000002",{exact:true})).not.toBeChecked()
  await expect(page.getByLabel("Zaznacz CASE-00000003",{exact:true})).not.toBeChecked()
})

test("bulk retry after a lost response keeps the frozen selection and idempotency key", async ({page}) => {
  await preparePage(page)
  const writes: Array<{body:unknown;key:string}> = []
  await page.route("**/api/v1/cases/bulk", async route => {
    writes.push({body:route.request().postDataJSON(),key:route.request().headers()["idempotency-key"]})
    if(writes.length===1) return route.abort("connectionreset")
    await route.fulfill({json:{items:[{caseId,status:"SUCCEEDED",code:"OK"}]}})
  })
  await page.goto("/cases")
  await page.getByLabel("Zaznacz CASE-00000002",{exact:true}).check()
  await page.getByLabel("Operacje zbiorcze",{exact:true}).getByRole("button",{name:"Ignoruj",exact:true}).click()
  const dialog=page.getByRole("dialog")
  await dialog.getByRole("button",{name:"Zatwierdź",exact:true}).click()
  await expect(dialog.getByRole("alert")).toContainText("Ponów tę samą operację")
  await dialog.getByRole("button",{name:"Ponów",exact:true}).click()
  await expect(dialog).not.toBeVisible()
  expect(writes).toHaveLength(2)
  expect(writes[1]).toEqual(writes[0])
})

test("changing Case filters clears a previous explicit selection", async ({page}) => {
  await preparePage(page)
  await page.goto("/cases")
  await page.getByLabel("Zaznacz CASE-00000002",{exact:true}).check()
  await page.getByRole("button",{name:"Moje",exact:true}).click()
  await expect(page.getByLabel("Operacje zbiorcze",{exact:true})).toHaveCount(0)
  await page.getByRole("button",{name:"Wszystkie",exact:true}).click()
  await expect(page.getByLabel("Zaznacz CASE-00000002",{exact:true})).not.toBeChecked()
})

test("bulk Assign uses an eligible-user picker and the protected bulk contract", async ({page}) => {
  await preparePage(page)
  await page.route("**/api/v1/auth/me", route=>route.fulfill({json:{...authenticatedSession,effectivePermissions:["manage_users","reassign_cases"]}}))
  await page.route(`**/api/v1/admin/cases/${caseId}/assignees`, route=>route.fulfill({json:[{id:authenticatedSession.id,displayName:"Anna Kowalska",eligible:true,ignoreWeight:1,ignoreWeightValidUntil:null}]}))
  const writes: unknown[] = []
  await page.route("**/api/v1/cases/bulk", async route=>{
    writes.push(route.request().postDataJSON())
    expect(route.request().headers()["x-xsrf-token"]).toBe("e2e-csrf")
    await route.fulfill({json:{items:[{caseId,status:"SUCCEEDED",code:"OK"}]}})
  })
  await page.goto("/cases")
  await page.getByLabel("Zaznacz CASE-00000002",{exact:true}).check()
  await page.getByLabel("Operacje zbiorcze",{exact:true}).getByRole("button",{name:"Przypisz",exact:true}).click()
  const dialog = page.getByRole("dialog")
  await dialog.getByLabel("Agent",{exact:true}).selectOption(authenticatedSession.id)
  await dialog.getByRole("button",{name:"Zatwierdź",exact:true}).click()
  await expect(dialog).not.toBeVisible()
  expect(writes).toEqual([{caseIds:[caseId],action:"ASSIGN",targetUserId:authenticatedSession.id}])
})

test("resolved Case indicators use neutral gray without changing the workflow enum", async ({page})=>{
  await preparePage(page)
  await page.route("**/api/v1/cases?**", route=>route.fulfill({json:{items:[{...caseListItem,status:"RESOLVED"}],nextCursor:null}}))
  await page.goto("/cases")
  const list = page.getByRole("button",{name:/Slack Test Customer/})
  const badge = list.getByText("Rozwiązane",{exact:true})
  await expect(badge).toHaveClass(/text-slate-300/)
  await expect(badge).not.toHaveClass(/text-red/)
})
