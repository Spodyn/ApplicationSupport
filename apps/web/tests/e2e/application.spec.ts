import { expect, test, type Page } from "@playwright/test"

const localOrigin = "http://127.0.0.1:3100"

const authenticatedSession = {
  id: "018f0000-0000-7000-8000-000000000064",
  email: "anna.kowalska@firma.pl",
  displayName: "Anna Kowalska",
  role: "USER",
  createdAt: "2024-01-12T08:00:00.000Z",
  effectivePermissions: [],
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

async function preparePage(page: Page, initiallyAuthenticated = true) {
  const externalRequests: string[] = []
  let authenticated = initiallyAuthenticated
  let claimed = false
  let read = false
  const supportMessages: Array<Omit<typeof inboundMessage, "deliveryStatus"> & { deliveryStatus: string | null }> = []
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
      authenticated = true
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(authenticatedSession),
      })
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/logout" && request.method() === "POST") {
      authenticated = false
      await route.fulfill({ status: 204 })
      return
    }

    if (requestUrl.pathname === "/api/v1/cases" && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        items: [{ ...caseListItem, status: claimed ? "VERIFICATION" : "NEW",
          ownerUserId: claimed ? authenticatedSession.id : null,
          ownerDisplayName: claimed ? authenticatedSession.displayName : null,
          unreadForCurrentUser: !read }], nextCursor: null,
      }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}` && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        id: caseId, reference: caseListItem.reference, status: claimed ? "VERIFICATION" : "NEW",
        customer: { id: "customer", name: caseListItem.customerName, externalRef: "slack-test" },
        owner: { id: claimed ? authenticatedSession.id : null, displayName: claimed ? authenticatedSession.displayName : null },
        channel: { id: "channel", name: "new-channel", externalChannelId: "C0C6N61M4HZ", groupingStrategy: "SLACK_ROOT_THREAD" },
        integration: { id: "integration", provider: "SLACK", displayName: "TestApp", workspaceExternalId: "team", workspaceName: "TestApp" },
        relatedCase: { id: null, reference: null }, personalState: { lastReadMessageId: read ? inboundMessageId : null, lastReadAt: null, snoozedUntil: null },
        sla: null, ignoreScore: 0, availableActions: claimed ? ["REPLY", "MARK_READ"] : ["CLAIM", "MARK_READ"],
        claimedAt: null, waitingUntil: null, resolvedAt: null, ignoredAt: null, resolutionCategory: null,
        createdAt: caseListItem.createdAt, updatedAt: caseListItem.updatedAt, lastActivityAt: caseListItem.lastActivityAt, version: claimed ? 2 : 1,
      }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/messages` && request.method() === "GET") {
      const before = requestUrl.searchParams.get("before")
      historyBefore.push(before)
      if (before === "older-cursor") {
        const olderMessage = {
          ...inboundMessage,
          id: "018f0000-0000-7000-8000-000000000199",
          body: "Older persisted message",
          providerCreatedAt: "2026-10-05T09:00:00Z",
          createdAt: "2026-10-05T09:00:00Z",
        }
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ items: [inboundMessage, olderMessage], nextCursor: null }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          items: [...supportMessages].reverse().concat(inboundMessage),
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
      const id = `018f0000-0000-7000-8000-00000000020${3 + supportMessages.length}`
      supportMessages.push({
        ...inboundMessage,
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
      if (message) message.deliveryStatus = "QUEUED"
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
  const chat = page.getByRole("option", { name: /Slack Test Customer/ })
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
    deliveryStatus: "FAILED",
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

test("formularz dodawania użytkownika otwiera się i resetuje po anulowaniu", async ({ page }) => {
  const { externalRequests } = await preparePage(page)
  await page.goto("/users")

  await page.getByRole("button", { name: "Dodaj użytkownika" }).click()
  const dialog = page.getByRole("dialog", { name: "Dodaj użytkownika" })
  await expect(dialog).toBeVisible()

  await dialog.getByLabel("Imię i nazwisko").fill("Test tymczasowy")
  await dialog.getByLabel("E-mail").fill("test@example.invalid")
  await dialog.getByRole("button", { name: "Anuluj" }).click()

  await page.getByRole("button", { name: "Dodaj użytkownika" }).click()
  const reopenedDialog = page.getByRole("dialog", { name: "Dodaj użytkownika" })
  await expect(reopenedDialog.getByLabel("Imię i nazwisko")).toHaveValue("")
  await expect(reopenedDialog.getByLabel("E-mail")).toHaveValue("")
  expect(externalRequests).toEqual([])
})

test("mobilny widok czatów przechodzi z listy do rozmowy i wraca", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { externalRequests } = await preparePage(page)
  await page.goto("/cases")

  const chatList = page.getByRole("region", { name: "Lista czatów" })
  await expect(chatList).toBeVisible()
  await page.getByRole("option", { name: /Slack Test Customer/ }).click()

  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation).toBeVisible()
  await conversation.getByRole("button", { name: "Wróć do listy" }).click()

  await expect(chatList).toBeVisible()
  expect(externalRequests).toEqual([])
})
