import { createHmac, randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'

const fixtures = new WeakMap<Page, {channelId: string; externalChannelId: string; workspaceId: string}>()

async function csrf(page: Page) {
  return (await page.context().cookies()).find(cookie => cookie.name === 'XSRF-TOKEN')?.value ?? ''
}

async function command(page: Page, path: string, data: unknown, method = 'POST', key: string = randomUUID()) {
  return page.request.fetch(path, {method, data,
    headers: {'X-XSRF-TOKEN': await csrf(page), 'Idempotency-Key': key}})
}

async function login(page: Page, email?: string, english = false) {
  if (!email) {
    const fixture = await (await page.request.post('/__test/account')).json()
    fixtures.set(page, fixture)
    email = fixture.email
  }
  await page.goto('/login')
  await page.getByLabel('Email').fill(email!)
  await page.getByLabel('Password').fill('test-only-fullstack-credential')
  await page.getByRole('button', {name: 'Sign in'}).click()
  await expect(page).toHaveURL(/\/cases$/)
  if (!english) await selectLanguage(page, "Polski", "en")
}

async function selectLanguage(page: Page, name: "Polski" | "English", from: "en" | "pl") {
  const trigger = page.getByRole("button", { name: from === "en" ? /Open profile menu:/ : /Otwórz menu profilu:/ })
  await trigger.click()
  const submenu = page.getByRole("menuitem", { name: from === "en" ? /Interface language/ : /Język interfejsu/ })
  await submenu.focus()
  await page.keyboard.press("ArrowRight")
  await page.getByRole("menuitemradio", { name, exact: true }).click()
  await expect(page.locator("html")).toHaveAttribute("lang", name === "Polski" ? "pl" : "en")
}

async function inbound(page: Page, text: string, thread?: string) {
  const fixture = fixtures.get(page)!
  const ts = (Date.now() / 1000).toFixed(6)
  const payload = JSON.stringify({type: 'event_callback', team_id: fixture.workspaceId, event_id: `Ev-${randomUUID()}`,
    event: {type: 'message', channel_type: 'channel', channel: fixture.externalChannelId, user: 'UTESTHUMAN',
      text, ts, ...(thread ? {thread_ts: thread} : {})}})
  const timestamp = String(Math.floor(Date.now() / 1000))
  const signature = 'v0=' + createHmac('sha256', 'test-only-fullstack-signing').update(`v0:${timestamp}:${payload}`).digest('hex')
  const response = await page.request.post('/api/v1/providers/slack/events', {
    data: payload, headers: {'Content-Type': 'application/json', 'X-Slack-Request-Timestamp': timestamp, 'X-Slack-Signature': signature},
  })
  expect(response.status()).toBe(200)
  return {ts, payload, timestamp, signature}
}

test('real login → customer mapping → signed intake → Claim → Reply → realtime → refresh → logout', async ({page}) => {
  const frames: string[] = []
  page.on('websocket', socket => socket.on('framereceived', frame => frames.push(String(frame.payload))))
  await login(page)
  await expect.poll(() => frames.some(frame => frame.startsWith('CONNECTED'))).toBe(true)
  const fixture = fixtures.get(page)!
  const marker = randomUUID().slice(0, 8)
  const customer = await command(page, '/api/v1/admin/customers', {name: `Regression ${marker}`, externalRef: `fixture-${marker}`})
  expect(customer.status()).toBe(201)
  const customerId = (await customer.json()).id
  expect((await command(page, `/api/v1/admin/channels/${fixture.channelId}`, {customerId, groupingStrategy: 'SLACK_ROOT_THREAD'}, 'PATCH')).status()).toBe(200)
  const first = await inbound(page, `Synthetic request ${marker}`)
  const option = page.getByRole('button', {name: new RegExp(`Regression ${marker}`)})
  await expect(option).toContainText(`Synthetic request ${marker}`)
  const list = await (await page.request.get('/api/v1/cases')).json()
  const caseId = list.items.find((item: {lastMessagePreview: string}) => item.lastMessagePreview === `Synthetic request ${marker}`).id
  await option.click()
  const conversation = page.getByRole('region', {name: `Rozmowa Regression ${marker}`})
  await conversation.getByRole('button', {name: 'Przejmij', exact: true}).click()
  await conversation.getByLabel('Treść odpowiedzi').fill(`Synthetic answer ${marker}`)
  const sendResponse = page.waitForResponse(response => response.url().endsWith(`/cases/${caseId}/messages`) && response.request().method() === 'POST')
  await conversation.getByLabel('Treść odpowiedzi').press('Enter')
  const sent = await sendResponse
  expect(sent.status()).toBe(202)
  const sentBody = await sent.json()
  await expect.poll(async () => {
    const history = await (await page.request.get(`/api/v1/cases/${caseId}/messages`)).json()
    return history.items.find((item: {id: string}) => item.id === sentBody.messageId)?.deliveryStatus
  }).toBe('SENT')
  const replay = await command(page, `/api/v1/cases/${caseId}/messages`, sent.request().postDataJSON(), 'POST', sent.request().headers()['idempotency-key'])
  expect(replay.status()).toBe(202)
  expect((await replay.json()).messageId).toBe(sentBody.messageId)
  await expect.poll(async () => (await (await page.request.get('/__test/observed')).json()).messages
    .filter((item: {clientMessageId: string}) => item.clientMessageId === sentBody.messageId).length).toBe(1)
  // Provider duplicate must not create another Case or Message.
  expect((await page.request.post('/api/v1/providers/slack/events', {data: first.payload, headers: {
    'Content-Type': 'application/json', 'X-Slack-Request-Timestamp': first.timestamp, 'X-Slack-Signature': first.signature,
  }})).status()).toBe(200)
  await inbound(page, `Synthetic follow-up ${marker}`, first.ts)
  await expect(conversation.getByText(`Synthetic follow-up ${marker}`, {exact: true})).toBeVisible()
  expect(frames.some(frame => frame.includes('MESSAGE') && frame.includes(caseId))).toBe(true)
  await page.reload()
  await expect(page.getByText(`Synthetic answer ${marker}`, {exact: true})).toBeVisible()
  await expect.poll(() => frames.filter(frame => frame.startsWith('CONNECTED')).length).toBeGreaterThanOrEqual(2)
  const history = await (await page.request.get(`/api/v1/cases/${caseId}/messages`)).json()
  expect(history.items.filter((item: {body: string}) => item.body === `Synthetic request ${marker}`)).toHaveLength(1)
  const logout = await command(page, '/api/v1/auth/logout', undefined)
  expect(logout.status()).toBe(204)
  expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)
  await page.reload()
  await expect(page).toHaveURL(/\/login$/)
})

test('real session cookies and CSRF reject cross-boundary mutations; service worker does not cache API', async ({page, context}) => {
  await login(page)
  const cookie = (await context.cookies()).find(value => value.name === 'USI_SESSION')!
  expect(cookie.httpOnly).toBe(true)
  expect(cookie.sameSite).toBe('Lax')
  const denied = await page.request.post('/api/v1/admin/customers', {data: {name: 'Must not persist'}})
  expect(denied.status()).toBe(403)
  await page.evaluate(async () => { await navigator.serviceWorker.ready })
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true)
  // APIRequestContext bypasses service workers. Use actual controlled-page fetches.
  expect(await page.evaluate(async () => {
    const statuses = []
    for (const path of ['/api/v1/auth/me', '/api/v1/cases']) {
      const response = await fetch(path, {credentials: 'same-origin', cache: 'no-store'})
      await response.json()
      statuses.push(response.status)
    }
    return statuses
  })).toEqual([200, 200])
  const cachedPaths = await page.evaluate(async () => {
    const entries = await Promise.all((await caches.keys()).map(async key => (await (await caches.open(key)).keys()).map(request => new URL(request.url).pathname)))
    return entries.flat()
  })
  expect(cachedPaths).toContain('/manifest.webmanifest')
  expect(cachedPaths).toContain('/pwa/manifest/en')
  expect(cachedPaths).toContain('/pwa/manifest/pl')
  expect(cachedPaths.filter(path => path.startsWith('/api/'))).toEqual([])
  await context.setOffline(true)
  try {
    expect(await page.evaluate(async () => (await fetch('/manifest.webmanifest')).status)).toBe(200)
    expect(await page.evaluate(async () => (await (await fetch('/pwa/manifest/en')).json()).lang)).toBe('en')
    expect(await page.evaluate(async () => {
      try { return (await fetch('/api/v1/auth/me', {credentials: 'same-origin', cache: 'no-store'})).status }
      catch { return null }
    })).toBeNull()
  } finally { await context.setOffline(false) }
  expect(await page.evaluate(() => Object.keys(localStorage).filter(key => /session|token|password/i.test(key)))).toEqual([])
})

test('real MinIO uploads enforce owner access, quarantine downloads and session deactivation', async ({page, browser, baseURL}) => {
  await login(page)
  const marker = randomUUID().slice(0, 8)
  await inbound(page, `Attachment scenario ${marker}`)
  let caseId = ''
  await expect.poll(async () => {
    const data = await (await page.request.get('/api/v1/cases')).json()
    caseId = data.items.find((item: {lastMessagePreview: string}) => item.lastMessagePreview === `Attachment scenario ${marker}`)?.id ?? ''
    return caseId
  }).not.toBe('')
  expect((await command(page, `/api/v1/cases/${caseId}/claim`, undefined)).status()).toBe(200)
  const clean = await page.request.post(`/api/v1/cases/${caseId}/attachments`, {
    headers: {'X-XSRF-TOKEN': await csrf(page)}, multipart: {file: {name: 'evidence.txt', mimeType: 'text/plain', buffer: Buffer.from('synthetic clean attachment')}},
  })
  expect(clean.status()).toBe(201)
  const cleanId = (await clean.json()).attachmentId
  const downloaded = await page.request.get(`/api/v1/cases/${caseId}/attachments/${cleanId}`)
  expect(downloaded.status()).toBe(200)
  expect(await downloaded.text()).toBe('synthetic clean attachment')
  const quarantined = await page.request.post(`/api/v1/cases/${caseId}/attachments`, {
    headers: {'X-XSRF-TOKEN': await csrf(page)}, multipart: {file: {name: 'blocked.txt', mimeType: 'text/plain', buffer: Buffer.from('test-only-infected')}},
  })
  const quarantine = await quarantined.json()
  expect(quarantine.scanStatus).toBe('INFECTED')
  const blocked = await page.request.get(`/api/v1/cases/${caseId}/attachments/${quarantine.attachmentId}`)
  expect(blocked.status()).toBe(409)
  expect((await blocked.json()).code).toBe('CONFLICT')
  const agentEmail = `agent-${marker}@example.test`
  const created = await command(page, '/api/v1/admin/users', {fullName: 'Synthetic Agent', email: agentEmail, role: 'USER', active: true, permissions: []})
  expect(created.status()).toBe(201)
  const agentId = (await created.json()).id
  const invitation = await (await command(page, `/api/v1/admin/users/${agentId}/invitation`, undefined)).json()
  const agentContext = await browser.newContext({baseURL})
  try {
    const agent = await agentContext.newPage()
    await agent.goto('/login')
    expect((await agent.request.get('/api/v1/auth/me')).status()).toBe(401)
    expect(await csrf(agent)).not.toBe('')
    expect((await command(agent, '/api/v1/auth/invitations/accept', {token: invitation.token, password: 'test-only-fullstack-credential'})).status()).toBe(204)
    await login(agent, agentEmail)
    expect((await agent.request.get('/api/v1/admin/users')).status()).toBe(403)
    expect((await agent.request.post(`/api/v1/cases/${caseId}/attachments`, {headers: {'X-XSRF-TOKEN': await csrf(agent)},
      multipart: {file: {name: 'unauthorized.txt', mimeType: 'text/plain', buffer: Buffer.from('must not persist')}}})).status()).toBe(403)
    expect((await command(page, `/api/v1/admin/users/${agentId}/deactivate`, undefined, 'PATCH')).status()).toBe(200)
    await agent.reload()
    await expect(agent).toHaveURL(/\/login$/)
  } finally { await agentContext.close() }
})

test('archived Slack failure shows the actual reason and never offers an ineligible retry', async ({page}) => {
  await login(page)
  const marker = randomUUID().slice(0, 8)
  await inbound(page, `Archived scenario ${marker}`)
  const option = page.getByRole('button').filter({hasText: `Archived scenario ${marker}`})
  await expect(option).toBeVisible()
  await option.click()
  await page.getByRole('button', {name: 'Przejmij', exact: true}).click()
  await page.getByLabel('Treść odpowiedzi').fill(`test-only-archived ${marker}`)
  await page.getByRole('button', {name: 'Wyślij', exact: true}).click()
  const archiveNotice = 'Kanał Slack jest zarchiwizowany. Nie można wysyłać do niego wiadomości.'
  await expect(page.locator('[data-message-kind="support"]').getByText(archiveNotice)).toBeVisible()
  await expect(page.locator('[data-sonner-toast]').getByText(archiveNotice)).toHaveCount(1)
  await page.reload()
  await expect(page.locator('[data-message-kind="support"]').getByText(archiveNotice)).toBeVisible()
  await expect(page.locator('[data-sonner-toast]').getByText(archiveNotice)).toHaveCount(0)
  await expect(page.getByRole('button', {name: 'Ponów wysyłkę'})).toHaveCount(0)
})

test('Slack rate limiting schedules a retry and only one message is delivered', async ({page}) => {
  await login(page)
  const marker = randomUUID().slice(0, 8)
  await inbound(page, 'Rate limit scenario ' + marker)
  await page.getByRole('button').filter({hasText: 'Rate limit scenario ' + marker}).click()
  await page.getByRole('button', {name: 'Przejmij', exact: true}).click()
  const sent = page.waitForResponse(response => response.url().endsWith('/messages') && response.request().method() === 'POST')
  await page.getByLabel('Treść odpowiedzi').fill('test-only-rate-limit ' + marker)
  await page.getByLabel('Treść odpowiedzi').press('Enter')
  const response = await sent
  expect(response.status()).toBe(202)
  const {messageId} = await response.json()
  const bubble = page.locator('[data-message-kind="support"]').filter({hasText: 'test-only-rate-limit ' + marker})
  await expect(bubble.getByText(/Osiągnięto limit wysyłki Slack/)).toBeVisible()
  await expect(bubble.getByText(/Następna próba/)).toBeVisible()
  await expect(bubble.getByRole('button', {name: 'Ponów wysyłkę'})).toHaveCount(0)
  await expect(bubble.getByText(/Wysłano/)).toBeVisible()
  await expect.poll(async () => (await (await page.request.get('/__test/observed')).json()).messages
    .filter((item: {clientMessageId: string}) => item.clientMessageId === messageId).length).toBe(1)
})

test('real bulk workflow preserves per-Case rules, audit, realtime and replay', async ({page}) => {
  const frames: string[] = []
  page.on('websocket', socket => socket.on('framereceived', frame => frames.push(String(frame.payload))))
  await login(page)
  const marker = randomUUID().slice(0, 8)
  await inbound(page, `Bulk first ${marker}`)
  // Separate root timestamps produce two distinct Cases.
  await expect.poll(async () => (await (await page.request.get('/api/v1/cases')).json()).items
    .filter((item: {lastMessagePreview: string}) => item.lastMessagePreview === `Bulk first ${marker}`).length).toBe(1)
  await inbound(page, `Bulk second ${marker}`)
  const matching = async () => (await (await page.request.get('/api/v1/cases')).json()).items
    .filter((item: {lastMessagePreview: string}) => item.lastMessagePreview.includes(marker))
  await expect.poll(async () => (await matching()).length).toBe(2)
  const ids = (await matching()).map((item: {id: string}) => item.id)
  const missing = randomUUID()
  const selection = {caseIds: [...ids, missing], action: 'CLAIM', targetUserId: null}
  const key = randomUUID()
  const bulkMode = page.getByRole('button', { name: 'Operacje zbiorcze', exact: true })
  await expect(bulkMode).toBeVisible()
  await bulkMode.click()
  const bulkToolbar = page.getByRole('group', { name: 'Operacje zbiorcze', exact: true })
  await expect(bulkToolbar.getByText('Zaznaczono: 0')).toBeVisible()
  for (const item of await matching()) {
    await page.getByRole('checkbox', { name: 'Zaznacz ' + item.reference }).check()
  }
  await bulkToolbar.getByRole('button', { name: 'Przejmij', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Zatwierdź' }).click()
  await expect(page.getByText('Wynik: wykonano: 2; pominięto: 0; błędy: 0')).toBeVisible()
  selection.action = 'RESOLVE'
  const claim = await command(page, '/api/v1/cases/bulk', selection, 'POST', key)
  expect(claim.status()).toBe(200)
  const claimed = await claim.json()
  expect(claimed.items.map((item: {status: string}) => item.status)).toEqual(['SUCCEEDED','SUCCEEDED','SKIPPED'])
  expect(await (await command(page, '/api/v1/cases/bulk', selection, 'POST', key)).json()).toEqual(claimed)
  expect((await command(page, '/api/v1/cases/bulk', {...selection, action:'IGNORE'}, 'POST', key)).status()).toBe(409)
  await expect.poll(() => frames.some(frame => frame.includes('case.claimed'))).toBe(true)
  for (const id of ids) {
    const detail = await (await page.request.get(`/api/v1/cases/${id}`)).json()
    expect(detail.status).toBe('RESOLVED')
    const history = await (await page.request.get(`/api/v1/cases/${id}/activity`)).json()
    expect(history.items.filter((item: {action: string}) => item.action === 'CASE_RESOLVE')).toHaveLength(1)
    const replay = await command(page, '/api/v1/cases/bulk', selection, 'POST', key)
    expect(replay.status()).toBe(200)
    expect((await (await page.request.get(`/api/v1/cases/${id}`)).json()).version).toBe(detail.version)
  }
})

type TestAccount = { userId: string; email: string; displayName: string }

async function testAccount(page: Page, role: 'agent' | 'account' = 'agent'): Promise<TestAccount> {
  const response = await page.request.post('/__test/' + role)
  expect(response.ok()).toBe(true)
  return response.json()
}

test('English starts clean and the profile language follows each account in the same browser', async ({page}) => {
  const a = await testAccount(page)
  const b = await testAccount(page)
  const hydrationErrors: string[] = []
  page.on('pageerror', error => hydrationErrors.push(error.message))
  await login(page, a.email, true)
  await expect(page.getByRole('heading', {name: 'Chats'})).toBeVisible()
  await selectLanguage(page, 'Polski', 'en')
  await page.reload()
  await expect(page.getByRole('heading', {name: 'Czaty'})).toBeVisible()
  await page.getByRole('button', {name: /Otwórz menu profilu:/}).click()
  await page.getByRole('menuitem', {name: 'Wyloguj się'}).click()
  await expect(page).toHaveURL(/\/login$/)

  await login(page, b.email, true)
  await expect(page.getByRole('heading', {name: 'Chats'})).toBeVisible()
  await selectLanguage(page, 'English', 'en')
  await page.getByRole('button', {name: /Open profile menu:/}).click()
  await page.getByRole('menuitem', {name: 'Sign out'}).click()
  await expect(page).toHaveURL(/\/login$/)
  await login(page, a.email, true)
  await expect(page.getByRole('heading', {name: 'Czaty'})).toBeVisible()

  await page.setViewportSize({width: 390, height: 840})
  await page.getByRole('button', {name: 'Przełącz panel boczny'}).click()
  await page.getByRole('button', {name: new RegExp(a.displayName)}).click()
  await page.getByRole('menuitem', {name: /Język interfejsu/}).focus()
  await page.keyboard.press('ArrowRight')
  await page.getByRole('menuitemradio', {name: 'English', exact: true}).click()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  expect(hydrationErrors).toEqual([])
})

test('private messaging persists, reconnects and denies an unrelated administrator', async ({page, browser, baseURL}) => {
  const userA = await testAccount(page)
  const userB = await testAccount(page)
  const userC = await testAccount(page, 'account')
  const contextB = await browser.newContext({baseURL})
  const contextC = await browser.newContext({baseURL})
  const b = await contextB.newPage()
  const c = await contextC.newPage()
  const destination = '/api/v1/direct-messages/conversations'
  const receivedByA: string[] = []
  const receivedByB: string[] = []
  const receivedByC: string[] = []
  page.on('websocket', socket => socket.on('framereceived', frame => receivedByA.push(String(frame.payload))))
  b.on('websocket', socket => socket.on('framereceived', frame => receivedByB.push(String(frame.payload))))
  c.on('websocket', socket => socket.on('framereceived', frame => receivedByC.push(String(frame.payload))))
  await page.addInitScript(() => {
    const NativeSocket = window.WebSocket
    window.WebSocket = class extends NativeSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols)
        if (new URL(url).pathname === '/ws') Object.assign(window, { disconnectTestSocket: () => this.close() })
      }
    }
  })
  try {
    await Promise.all([login(page, userA.email, true), login(b, userB.email, true), login(c, userC.email, true)])
    expect((await page.request.get('/api/v1/admin/users')).status()).toBe(403)
    await page.getByRole('link', {name: 'Direct Messages', exact: true}).click()
    await b.getByRole('link', {name: 'Direct Messages', exact: true}).click()
    await page.getByRole('button', {name: 'New Message'}).click()
    await page.getByRole('textbox', {name: 'Search people'}).fill(userB.displayName)
    await page.getByRole('dialog').getByRole('button', {name: userB.displayName}).click()
    const threadA = page.getByRole('region', {name: 'Conversation ' + userB.displayName})
    await expect(threadA).toBeVisible()
    const id = new URL(page.url()).searchParams.get('conversation')!
    const path = destination + '/' + id + '/messages'
    const replayStart = await command(b, destination, {recipientId: userA.userId})
    expect((await replayStart.json()).id).toBe(id)

    const aText = threadA.getByRole('textbox', {name: 'Direct message text'})
    await aText.fill('Test private message')
    await aText.press('Enter')
    await expect(threadA.getByText('Test private message', {exact: true})).toBeVisible()
    await expect.poll(() => receivedByB.some(frame => frame.includes('direct_message.changed') && frame.includes(id))).toBe(true)
    expect(receivedByB.every(frame => !frame.includes('Test private message'))).toBe(true)
    const peer = b.getByRole('button', {name: new RegExp(userA.displayName)})
    await expect(peer.getByLabel('Unread: 1')).toBeVisible()
    await peer.click()
    const threadB = b.getByRole('region', {name: 'Conversation ' + userA.displayName})
    await expect(threadB.getByText('Test private message', {exact: true})).toBeVisible()
    await expect(peer.getByLabel('Unread: 1')).toHaveCount(0)
    const bText = threadB.getByRole('textbox', {name: 'Direct message text'})
    await bText.fill('Reply from B')
    await threadB.getByRole('button', {name: 'Send', exact: true}).click()
    await expect(threadA.getByText('Reply from B', {exact: true})).toBeVisible()

    await Promise.all([page.reload(), b.reload()])
    await expect(threadA.getByText('Reply from B', {exact: true})).toBeVisible()
    await expect(threadB.getByText('Test private message', {exact: true})).toBeVisible()
    expect((await c.request.get(path)).status()).toBe(404)
    expect((await c.request.get(destination + '/' + id)).status()).toBe(404)
    expect((await command(c, path, {senderId: userA.userId, body: 'Unauthorized'})).status()).toBe(404)
    expect((await c.request.post(path, {data: {body: 'No CSRF'}})).status()).toBe(403)
    expect(receivedByC.every(frame => !frame.includes(id))).toBe(true)
    const blocked = await c.evaluate(async (target) => new Promise<boolean>((resolve) => {
      const socket = new WebSocket('ws://' + location.host + '/ws')
      const deadline = setTimeout(() => { socket.close(); resolve(false) }, 6000)
      const finish = (value: boolean) => { clearTimeout(deadline); socket.close(); resolve(value) }
      socket.onopen = () => socket.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0')
      socket.onmessage = (event) => {
        const frame = String(event.data)
        if (frame.startsWith('CONNECTED')) {
          socket.send('SUBSCRIBE\nid:private-denial\ndestination:/user/' + target + '/queue/direct-messages\nreceipt:private-denial\n\n\0')
        } else if (frame.startsWith('ERROR')) finish(true)
        else if (frame.startsWith('RECEIPT') || frame.startsWith('MESSAGE')) finish(false)
      }
      socket.onclose = (event) => { if (event.code !== 1000) finish(true) }
    }), userA.userId)
    expect(blocked).toBe(true)

    const reconnectCount = receivedByA.filter(frame => frame.startsWith('CONNECTED')).length
    await page.evaluate(() => (window as Window & { disconnectTestSocket?: () => void }).disconnectTestSocket?.())
    const retryKey = randomUUID()
    const [first, retry] = await Promise.all([
      command(b, path, {body: 'Missed while offline'}, 'POST', retryKey),
      command(b, path, {body: 'Missed while offline'}, 'POST', retryKey),
    ])
    expect(first.status()).toBe(201)
    expect(await retry.json()).toEqual(await first.json())
    await expect.poll(() => receivedByA.filter(frame => frame.startsWith('CONNECTED')).length).toBeGreaterThan(reconnectCount)
    await expect(threadA.getByText('Missed while offline', {exact: true})).toHaveCount(1)
    expect(receivedByC.every(frame => !frame.includes(id))).toBe(true)
    const history = await page.request.get(path + '?limit=1')
    expect(history.headers()['cache-control']).toContain('no-store')
    const newest = await history.json()
    expect(newest.items[0].body).toBe('Missed while offline')
    const older = await (await page.request.get(path, {params: {cursor: newest.nextCursor}})).json()
    expect(older.items.map((item: {body: string}) => item.body)).toEqual(['Reply from B', 'Test private message'])
    await b.setViewportSize({width: 390, height: 840})
    await threadB.getByRole('button', {name: 'Back to list'}).click()
    await expect(peer).toBeVisible()
    await peer.click()
    await expect(threadB.getByText('Reply from B', {exact: true})).toBeVisible()
    expect(await b.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    expect((await command(c, '/api/v1/admin/users/' + userB.userId + '/deactivate', undefined, 'PATCH')).status()).toBe(200)
    expect((await command(b, path, {body: 'Disabled account'})).status()).toBe(401)
    expect((await page.request.get(path)).status()).toBe(200)
  } finally {
    await contextB.close()
    await contextC.close()
  }
})
