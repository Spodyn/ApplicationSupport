import { createHmac, randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { expect, test, type Page } from '@playwright/test'

type SlackFixture = { channelId: string; externalChannelId: string; workspaceId: string }
const fixtures = new WeakMap<Page, SlackFixture>()

test('administrator creates and edits a selected-team connection through the real API without browser secrets', async ({page},testInfo) => {
  await login(page)
  const marker=randomUUID().slice(0,8),name=`Teams onboarding ${marker}`,team=randomUUID()
  const customerResponse=await command(page,'/api/v1/admin/customers',{name:`Teams customer ${marker}`})
  expect(customerResponse.status()).toBe(201)
  const customer=await customerResponse.json()
  await page.goto('/settings')
  await page.getByRole('tab',{name:'Integracje',exact:true}).click()
  await page.getByRole('button',{name:'Dodaj Teams',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'Dodaj Teams',exact:true})
  await expect(dialog.getByRole('switch',{name:'Automatycznie wykrywaj kanały',exact:true})).toBeChecked()
  await dialog.getByRole('textbox',{name:'Nazwa połączenia',exact:true}).fill(name)
  await dialog.getByRole('textbox',{name:/^Link do zespołu Teams lub Team ID/}).fill(team)
  await dialog.getByRole('combobox',{name:'Domyślny klient',exact:true}).click()
  await page.getByRole('option',{name:customer.name,exact:true}).click()
  await dialog.getByRole('button',{name:'Zapisz konfigurację',exact:true}).click()
  await expect(dialog).not.toBeVisible()
  const card=page.locator('[data-slot="card"]').filter({hasText:name})
  await expect(card.getByText('Brakuje konfiguracji bota na VPS',{exact:true})).toBeVisible()
  await card.getByRole('button',{name:'Konfiguruj',exact:true}).click()
  const edit=page.getByRole('dialog',{name:'Konfiguruj Teams',exact:true})
  await expect(edit.getByRole('textbox',{name:/^Link do zespołu Teams lub Team ID/})).toHaveValue(team)
  await edit.getByRole('switch',{name:'Automatycznie wykrywaj kanały',exact:true}).uncheck()
  await edit.getByRole('button',{name:'Zapisz konfigurację',exact:true}).click()
  await expect(edit).not.toBeVisible()
  const list=await (await page.request.get('/api/v1/admin/integrations')).json()
  const connection=list.find((i:{displayName:string})=>i.displayName===name)
  expect(await (await page.request.get(`/api/v1/admin/integrations/${connection.id}/onboarding`)).json()).toMatchObject({teamId:team,defaultCustomerId:customer.id,automaticDiscovery:false,state:'MISSING_BOT_CONFIGURATION'})
  await page.screenshot({path:testInfo.outputPath('provider-onboarding.png'),animations:'disabled'})
})

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

async function inbound(page: Page, text: string | undefined, thread?: string,
  fields: Record<string, unknown> = {}, authorization?: {user_id: string; is_bot: boolean}) {
  const fixture = fixtures.get(page)!
  const ts = fields.ts as string ?? (Date.now() / 1000).toFixed(6)
  const payload = JSON.stringify({type: 'event_callback', team_id: fixture.workspaceId, event_id: `Ev-${randomUUID()}`,
    ...(authorization ? {authorizations: [authorization]} : {}),
    event: {type: 'message', channel_type: fixture.externalChannelId.startsWith('G') ? 'group' : 'channel',
      channel: fixture.externalChannelId, user: 'UTESTHUMAN', text, ts, ...(thread ? {thread_ts: thread} : {}), ...fields}})
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
  const sourceSentAt = ((Date.now() - 120_000) / 1000).toFixed(6)
  const first = await inbound(page, `Synthetic request ${marker}`, undefined, {ts: sourceSentAt})
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
  const profile = await (await page.request.get('/api/v1/auth/me')).json()
  const casePath = '/api/v1/cases/' + caseId
  expect(await (await page.request.get(casePath)).json())
    .toMatchObject({workflowStatus: 'WAITING_ON_CUSTOMER', owner: {id: profile.id}, waitingUntil: expect.any(String)})
  const replay = await command(page, `/api/v1/cases/${caseId}/messages`, sent.request().postDataJSON(), 'POST', sent.request().headers()['idempotency-key'])
  expect(replay.status()).toBe(202)
  expect((await replay.json()).messageId).toBe(sentBody.messageId)
  await expect.poll(async () => (await (await page.request.get('/__test/observed')).json()).messages
    .filter((item: {clientMessageId: string}) => item.clientMessageId === sentBody.messageId).length).toBe(1)
  // Provider duplicate must not create another Case or Message.
  expect((await page.request.post('/api/v1/providers/slack/events', {data: first.payload, headers: {
    'Content-Type': 'application/json', 'X-Slack-Request-Timestamp': first.timestamp, 'X-Slack-Signature': first.signature,
  }})).status()).toBe(200)
  const followup = await inbound(page, `Synthetic follow-up ${marker}`, first.ts)
  await expect(conversation.getByText(`Synthetic follow-up ${marker}`, {exact: true})).toBeVisible()
  const readSla = async () => (await (await page.request.get(casePath)).json())
  const opened = await readSla()
  expect(opened).toMatchObject({workflowStatus: 'OPEN', owner: {id: profile.id}, waitingUntil: null,
    responseSla: {kind: 'NRT', lastSender: 'CUSTOMER'},
    sla: {firstResponseCompletedAt: expect.any(String)},
    responseCycles: [expect.objectContaining({endedAt: null})]})
  expect(Date.parse(opened.sla.firstResponseStartedAt)).toBe(Math.floor(Number(sourceSentAt) * 1000))
  const round = opened.responseCycles[0]
  const activeRound = opened.operationalDeadline
  expect(activeRound.kind).toBe('NRT')
  const failure = await command(page, casePath + '/messages', {body: 'test-only-archived'})
  expect(failure.status()).toBe(202)
  const failureId = (await failure.json()).messageId
  await expect.poll(async () => (await (await page.request.get(casePath + '/messages')).json()).items
    .find((item: {id: string}) => item.id === failureId)?.deliveryStatus).toBe('FAILED')
  expect((await readSla()).responseCycles).toEqual([round])
  expect((await readSla()).operationalDeadline.id).toBe(activeRound.id)

  expect((await page.request.post('/api/v1/providers/slack/events', {data: followup.payload, headers: {
    'Content-Type': 'application/json', 'X-Slack-Request-Timestamp': followup.timestamp, 'X-Slack-Signature': followup.signature,
  }})).status()).toBe(200)
  await inbound(page, 'Second message in the same NRT series', first.ts)
  await expect(conversation.getByText('Second message in the same NRT series', {exact: true})).toBeVisible()
  expect((await readSla()).responseCycles).toEqual([round])
  expect((await readSla()).operationalDeadline.id).not.toBe(activeRound.id)

  expect((await command(page, casePath + '/messages', {body: 'Successful NRT reply'})).status()).toBe(202)
  await expect.poll(async () => (await readSla()).responseSla.state).toBe('PAUSED')
  expect((await readSla()).responseCycles).toEqual([expect.objectContaining({id: round.id, outcome: 'RESPONDED'})])
  await inbound(page, 'First message in the next NRT series', first.ts)
  await expect.poll(async () => (await readSla()).responseCycles.length).toBe(2)
  expect((await readSla()).responseCycles[0].endedAt).toBeNull()
  await expect(option).toContainText('NRT')
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

test('shared countdown changes without requests and follow-up expiry reaches only the owner', async ({page, browser, baseURL}) => {
  await page.setViewportSize({width: 1440, height: 900})
  await page.clock.install()
  const owner = await testAccount(page, 'account')
  const viewer = await testAccount(page)
  fixtures.set(page, owner)
  await login(page, owner.email)
  const label = 'Operational reminder ' + randomUUID().slice(0, 8)
  await inbound(page, label)
  const initialTile = page.getByRole('button').filter({hasText: label})
  await initialTile.click()
  const caseId = (await initialTile.getAttribute('data-case-id'))!
  const tile = page.locator(`button[data-case-id="${caseId}"]`)
  const path = '/api/v1/cases/' + caseId
  const detail = async () => (await page.request.get(path)).json()
  const panel = page.getByRole('region', {name: /^Rozmowa /})
  const details = page.getByRole('complementary', {name: 'Szczegóły sprawy'})
  await panel.getByRole('button', {name: 'Przejmij', exact: true}).click()
  await expect(panel.getByLabel('Treść odpowiedzi')).toBeEnabled()
  expect((await command(page, path + '/messages', {body: 'Synthetic public reply'})).status()).toBe(202)
  const badges = [tile, panel, details].map(element => element.locator('[data-sla-countdown]'))
  for (const badge of badges) await expect(badge).toContainText('Kontakt z klientem')
  await page.waitForLoadState('networkidle')
  const requestPaths: string[] = []
  page.on('request', event => { if (event.url().includes('/api/v1/')) requestPaths.push(event.url()) })
  const before = await Promise.all(badges.map(badge => badge.textContent()))
  await page.clock.runFor(2_200)
  for (const [index, badge] of badges.entries()) await expect(badge).not.toHaveText(before[index]!)
  expect(requestPaths).toHaveLength(0)

  const reminderPath = '/api/v1/notifications/sla-reminders'
  const reminders = async (tab: Page) => (await (await tab.request.get(reminderPath)).json()).items
  expect(await (await page.request.post('/__test/expire-deadline', {data: {caseId}})).json()).toEqual({changed: 1})
  await expect.poll(async () => (await reminders(page)).filter((item: {caseId: string}) => item.caseId === caseId))
    .toEqual([expect.objectContaining({kind: 'FOLLOW_UP', unread: true})])
  const reminder = (await reminders(page)).find((item: {caseId: string}) => item.caseId === caseId)
  const viewerSession = await browser.newContext({baseURL})
  try {
    const other = await viewerSession.newPage()
    await login(other, viewer.email, true)
    expect((await reminders(other)).some((item: {caseId: string}) => item.caseId === caseId)).toBe(false)
    const denied = await command(other, reminderPath + '/read', {deadlineId: reminder.id})
    expect(await denied.json()).toEqual({updatedCount: 0})
  } finally {
    await viewerSession.close()
  }
  await expect(panel.locator('[data-sla-countdown]')).toContainText('Po terminie')
  await page.getByRole('button', {name: 'SLA', exact: true}).click()
  const attention = async () => (await (await page.request.get('/api/v1/cases?quickView=SLA')).json())
  await expect(tile).toBeVisible()
  expect((await attention()).items).toEqual(expect.arrayContaining([expect.objectContaining({id: caseId, slaState: 'FOLLOW_UP_DUE'})]))
  await expect(page.getByLabel('Liczba spraw')).toHaveText(String((await attention()).quickViewCounts.sla))
  expect(await detail()).toMatchObject({
    workflowStatus: 'WAITING_ON_CUSTOMER', owner: {id: owner.userId},
    operationalDeadline: {kind: 'FOLLOW_UP', state: 'FOLLOW_UP_DUE'},
  })
  await page.getByRole('button', {name: 'Powiadomienia', exact: true}).click()
  await page.getByRole('button', {name: /Czas skontaktować się z klientem/}).filter({hasText: (await detail()).reference}).click()
  await expect.poll(async () => (await reminders(page)).filter((item: {caseId: string}) => item.caseId === caseId))
    .toEqual([expect.objectContaining({kind: 'FOLLOW_UP', unread: false})])
  expect((await command(page, path + '/resolve', {})).status()).toBe(200)
  await expect.poll(detail).toMatchObject({workflowStatus: 'RESOLVED', operationalDeadline: null})
  await expect(tile).toHaveCount(0)
})

test('force resolve requires administrator authority and concurrent replies share a linked successor', async ({page, browser, baseURL}) => {
  const admin = await testAccount(page, 'account')
  const agent = await testAccount(page)
  fixtures.set(page, admin)
  const agentContext = await browser.newContext({baseURL})
  const other = await agentContext.newPage()
  try {
    await Promise.all([login(page, admin.email), login(other, agent.email, true)])
    const marker = randomUUID().slice(0, 8)
    const originalText = 'Original thread ' + marker
    const root = await inbound(page, originalText)
    const queue = async () => (await (await page.request.get('/api/v1/cases', {
      params: {search: admin.externalChannelId},
    })).json()).items
    await expect.poll(async () => (await queue()).length).toBe(1)
    const caseId = (await queue())[0].id
    const path = '/api/v1/cases/' + caseId
    const endpoint = '/api/v1/admin/cases/' + caseId + '/force-resolve'
    const detail = async () => await (await page.request.get(path)).json()
    const activity = async () => (await (await page.request.get(path + '/activity')).json()).items
    await other.goto('/cases?caseId=' + caseId)
    await expect(other.getByText(originalText, {exact: true})).toBeVisible()
    const before = await detail()
    expect((await command(other, endpoint, {resolutionCategory: 'OTHER'})).status()).toBe(403)
    expect(await detail()).toMatchObject({status: 'NEW', version: before.version})
    expect((await activity()).filter((event: {action: string}) => event.action === 'CASE_FORCE_RESOLVE')).toHaveLength(0)

    const key = randomUUID()
    const request = {resolutionCategory: 'OTHER'}
    const resolved = await command(page, endpoint, request, 'POST', key)
    expect(resolved.status()).toBe(200)
    const replay = await command(page, endpoint, request, 'POST', key)
    expect(replay.status()).toBe(200)
    expect(await replay.json()).toEqual(await resolved.json())
    await expect(other.getByRole('region', {name: /^Conversation /}).getByText('Resolved', {exact: true})).toBeVisible()
    const closed = await detail()
    expect(closed).toMatchObject({status: 'RESOLVED', owner: {id: null}, resolutionCategory: 'OTHER'})
    expect((await command(page, endpoint, request)).status()).toBe(409)
    expect((await activity()).filter((event: {action: string}) => event.action === 'CASE_FORCE_RESOLVE'))
      .toEqual([expect.objectContaining({actorType: 'ADMIN', actorUserId: admin.userId})])

    const replies = ['Post-resolution one ' + marker, 'Post-resolution two ' + marker]
    await Promise.all(replies.map((body, index) => inbound(page, body, root.ts, {
      ts: (Number(root.ts) + (index + 1) / 1000).toFixed(6),
    })))
    await expect.poll(async () => (await queue()).length).toBe(2)
    const next = (await queue()).find((item: {id: string}) => item.id !== caseId)
    expect(next.status).toBe('NEW')
    const nextPath = '/api/v1/cases/' + next.id
    expect(await (await page.request.get(nextPath)).json()).toMatchObject({relatedCase: {id: caseId}})
    const reopen = '/api/v1/admin/cases/' + caseId + '/reopen'
    expect((await command(other, reopen, undefined)).status()).toBe(403)
    await page.goto('/cases?caseId=' + caseId)
    await page.getByRole('button', {name: 'Cofnij rozwiązanie', exact: true}).click()
    const redirectResponse = page.waitForResponse(reply => reply.url().endsWith('/reopen') && reply.request().method() === 'POST')
    await page.getByRole('dialog').getByRole('button', {name: 'Cofnij rozwiązanie', exact: true}).click()
    const redirected = await redirectResponse
    expect(redirected.status()).toBe(200)
    expect(await redirected.json()).toEqual({caseId: next.id, changed: false})
    await expect(page).toHaveURL('/cases?caseId=' + next.id)
    expect((await activity()).filter((event: {action: string}) => event.action === 'CASE_REOPEN_REDIRECTED')).toHaveLength(1)
    const messages = async (casePath: string) => (await (await page.request.get(casePath + '/messages')).json())
      .items.map((item: {body: string}) => item.body).sort()
    await expect.poll(() => messages(nextPath)).toEqual([...replies].sort())
    expect(await messages(path)).toEqual([originalText])
    expect(await detail()).toMatchObject({status: 'RESOLVED', version: closed.version, resolvedAt: closed.resolvedAt})
    await other.reload()
    await expect(other.getByRole('region', {name: /^Conversation /}).getByText('Resolved', {exact: true})).toBeVisible()
  } finally {
    await agentContext.close()
  }
})

test('undo ordinary resolution restarts claim SLA and allows a subsequent public reply', async ({page}) => {
  const owner = await testAccount(page, 'account')
  fixtures.set(page, owner)
  await login(page, owner.email)
  const marker = 'Undo ordinary resolution ' + randomUUID().slice(0, 8)
  const root = await inbound(page, marker)
  await page.getByRole('button').filter({hasText: marker}).click()
  const list = await (await page.request.get('/api/v1/cases', {params: {search: owner.externalChannelId}})).json()
  const caseId = list.items[0].id
  const path = '/api/v1/cases/' + caseId
  const detail = async () => (await (await page.request.get(path)).json())
  const send = async (body: string) => {
    const response = await command(page, path + '/messages', {body})
    expect(response.status()).toBe(202)
    await expect.poll(async () => (await detail()).workflowStatus).toBe('WAITING_ON_CUSTOMER')
  }
  expect((await command(page, path + '/claim', undefined)).status()).toBe(200)
  await send('First delivered reply')
  await inbound(page, 'Customer reply before ordinary resolve', root.ts)
  await expect.poll(async () => (await detail()).workflowStatus).toBe('OPEN')
  expect(await (await page.request.post('/__test/breach', {data: {caseId}})).json()).toEqual({changed: 1})
  expect((await command(page, path + '/resolve', {resolutionCategory: 'SOLVED'})).status()).toBe(200)
  const closed = await detail()
  await page.getByRole('button', {name: 'Cofnij rozwiązanie', exact: true}).click()
  const response = page.waitForResponse(reply => reply.url().endsWith('/reopen') && reply.request().method() === 'POST')
  await page.getByRole('dialog').getByRole('button', {name: 'Cofnij rozwiązanie', exact: true}).click()
  const reopened = await response
  expect(reopened.status()).toBe(200)
  expect(await reopened.json()).toEqual({caseId, changed: true})
  const replay = await command(page, '/api/v1/admin/cases/' + caseId + '/reopen', undefined, 'POST',
    reopened.request().headers()['idempotency-key'])
  expect(await replay.json()).toEqual({caseId, changed: true})
  expect(await detail()).toMatchObject({
    workflowStatus: 'NEW', owner: {id: null}, resolvedAt: null, version: closed.version + 1,
    sla: {firstResponseCompletedAt: closed.sla.firstResponseCompletedAt, unclaimedCompletedAt: null},
  })
  expect((await detail()).sla.unclaimedStartedAt > closed.sla.unclaimedStartedAt).toBe(true)
  const events = (await (await page.request.get(path + '/activity')).json()).items
  expect(events.filter((event: {action: string}) => event.action === 'CASE_REOPEN'))
    .toEqual([expect.objectContaining({historicalSlaBreach: true})])
  expect(events.filter((event: {action: string}) => event.action === 'CASE_RESOLVE')).toHaveLength(1)
  expect((await command(page, path + '/claim', undefined)).status()).toBe(200)
  await send('Delivered after reopening')
  expect(await detail()).toMatchObject({workflowStatus: 'WAITING_ON_CUSTOMER', owner: {id: owner.userId}})
})

test('undo and inbound race converge on one active Case for the provider thread', async ({page}) => {
  const admin = await testAccount(page, 'account')
  fixtures.set(page, admin)
  await login(page, admin.email)
  const marker = 'Reopen race ' + randomUUID().slice(0, 8)
  const root = await inbound(page, marker)
  await expect(page.getByRole('button').filter({hasText: marker})).toBeVisible()
  const list = async () => (await (await page.request.get('/api/v1/cases', {
    params: {search: admin.externalChannelId},
  })).json()).items
  const original = (await list())[0]
  const base = '/api/v1/admin/cases/' + original.id
  expect((await command(page, base + '/force-resolve', {})).status()).toBe(200)
  const body = 'Incoming during undo ' + marker
  const [response] = await Promise.all([command(page, base + '/reopen', undefined), inbound(page, body, root.ts)])
  expect(response.status()).toBe(200)
  const result = await response.json()
  const active = async () => (await list()).filter((item: {workflowStatus: string}) => item.workflowStatus !== 'RESOLVED')
  await expect.poll(async () => (await active()).map((item: {id: string}) => item.id)).toEqual([result.caseId])
  const history = async () => (await (await page.request.get('/api/v1/cases/' + result.caseId + '/messages')).json()).items
  await expect.poll(async () => (await history()).filter((item: {body: string}) => item.body === body).length).toBe(1)
  expect(result.changed).toBe(result.caseId === original.id)
})

for (const visibility of ['public', 'private'] as const) {
  test(`Slack ${visibility} inbound keeps broadcast replies and independent attachment states in one Case`, async ({page}) => {
    const fixture = await (await page.request.post(visibility === 'public' ? '/__test/account' : '/__test/private-account')).json()
    fixtures.set(page, fixture)
    await login(page, fixture.email)
    const marker = randomUUID().slice(0, 8)
    const channelCases = async () => (await (await page.request.get('/api/v1/cases')).json()).items
      .filter((item: {externalChannelId: string}) => item.externalChannelId === fixture.externalChannelId)
    const root = await inbound(page, `Inbox ${marker} :eyes:`)
    await expect(page.getByRole('button', {name: new RegExp(`Inbox ${marker} 👀`)})).toBeVisible()
    await expect.poll(async () => (await channelCases()).length).toBe(1)
    const caseId = (await channelCases())[0].id
    const panel = page.getByRole('region', {name: /^Rozmowa /})
    await page.getByRole('button', {name: new RegExp(`Inbox ${marker} 👀`)}).click()
    const history = async () => (await (await page.request.get(`/api/v1/cases/${caseId}/messages`)).json()).items
    const fileId = `F-inbound-${marker}`
    const deniedId = `F-inbound-denied-${marker}`
    const infectedId = `F-inbound-infected-${marker}`
    const scanErrorId = `F-inbound-scan-error-${marker}`
    const cleanOnlyId = `F-inbound-clean-only-${marker}`
    const missingScopeId = `F-inbound-missing-scope-${marker}`
    const broadcast = `Broadcast ${marker} :eyes: :unknown_code: 👋`
    const normal = `Ordinary reply ${marker}`

    await inbound(page, normal, root.ts)
    await expect(panel.getByText(normal, {exact: true})).toBeVisible()
    const reply = await inbound(page, broadcast, root.ts, {
      subtype: 'thread_broadcast', root: {text: 'Root snapshot', files: [{id: 'F-never-import-root'}]},
    })
    await expect(panel.getByText(`Broadcast ${marker} 👀 :unknown_code: 👋`, {exact: true})).toBeVisible()
    await inbound(page, broadcast, root.ts, {ts: reply.ts, files: [{id: fileId, mode: 'file_access', file_access: 'check_file_info'}]})
    expect((await page.request.post('/api/v1/providers/slack/events', {data: reply.payload, headers: {
      'Content-Type': 'application/json', 'X-Slack-Request-Timestamp': reply.timestamp, 'X-Slack-Signature': reply.signature,
      'X-Slack-Retry-Num': '1', 'X-Slack-Retry-Reason': 'http_timeout',
    }})).status()).toBe(200)
    await expect(panel.getByRole('link', {name: new RegExp(fileId)})).toBeVisible()
    const downloaded = await page.request.get((await panel.getByRole('link', {name: new RegExp(fileId)}).getAttribute('href'))!)
    expect(await downloaded.text()).toBe(`Synthetic inbound ${fileId}`)
    const echoTs = (Number(reply.ts) + 0.000001).toFixed(6)
    await inbound(page, 'Bot echo', root.ts, {ts: echoTs, subtype: 'thread_broadcast', user: 'UBOT'}, {user_id: 'UBOT', is_bot: true})
    await inbound(page, undefined, root.ts, {files: [{id: cleanOnlyId}]})
    await expect(panel.getByRole('link', {name: new RegExp(cleanOnlyId)})).toBeVisible()
    await inbound(page, undefined, root.ts, {
      files: [{id: deniedId, name: 'download-denied.txt'}, {id: infectedId}, {id: scanErrorId}, {id: missingScopeId}],
    })
    await expect(panel.getByText('download-denied.txt · Nie udało się pobrać pliku od dostawcy.', {exact: true})).toBeVisible()
    await expect(panel.getByText(/W pliku wykryto zagrożenie/)).toBeVisible()
    await expect(panel.getByText(`${missingScopeId} · Nie udało się pobrać pliku od dostawcy.`, {exact: true})).toBeVisible()
    await expect(panel.getByText(/Nie udało się zeskanować pliku/)).toBeVisible()
    const caption = `Caption ${marker}`
    await inbound(page, caption, root.ts, {subtype: 'thread_broadcast', files: [{id: fileId}, {id: fileId}]})
    await expect(panel.getByText(caption, {exact: true})).toBeVisible()
    await expect.poll(async () => (await history()).find((item: {body: string}) => item.body === caption)?.attachments[0]?.scanStatus).toBe('CLEAN')

    const messages = await history()
    expect(messages).toHaveLength(6)
    expect(messages.filter((item: {body: string}) => item.body === broadcast)).toHaveLength(1)
    expect(messages.find((item: {body: string}) => item.body === broadcast).attachments).toHaveLength(1)
    const fileOnly = messages.filter((item: {body: string}) => item.body === '')
    expect(fileOnly).toHaveLength(2)
    expect(fileOnly.find((item: {attachments: unknown[]}) => item.attachments.length === 1).attachments)
      .toEqual([expect.objectContaining({fileName: `${cleanOnlyId}.txt`, scanStatus: 'CLEAN'})])
    const failedFiles = fileOnly.find((item: {attachments: unknown[]}) => item.attachments.length === 4).attachments
    expect(failedFiles).toEqual(expect.arrayContaining([
      expect.objectContaining({fileName: 'download-denied.txt', scanStatus: 'ERROR', failureStage: 'DOWNLOAD'}),
      expect.objectContaining({scanStatus: 'INFECTED'}),
      expect.objectContaining({scanStatus: 'ERROR', failureStage: 'SCAN'}),
    ]))
    const denied = failedFiles.find((item: {fileName: string}) => item.fileName === 'download-denied.txt')
    expect((await page.request.get(`/api/v1/cases/${caseId}/attachments/${denied.id}`)).status()).toBe(409)
    expect(await channelCases()).toHaveLength(1)
    await page.goto(`/cases?caseId=${caseId}`)
    await page.reload()
    await expect(panel.getByText(caption, {exact: true})).toBeVisible()
    await expect(panel.getByText(/download-denied.txt/)).toBeVisible()
  })
}

test('internal Case notes stay in USI with independent drafts, realtime and public delivery', async ({page, browser, baseURL}) => {
  const author = await testAccount(page, 'account')
  const viewer = await testAccount(page)
  fixtures.set(page, author)
  const otherContext = await browser.newContext({baseURL})
  const other = await otherContext.newPage()
  try {
    await Promise.all([login(page, author.email), login(other, viewer.email, true)])
    const marker = 'Internal note intake ' + randomUUID().slice(0, 8)
    const root = await inbound(page, marker)
    const tile = page.getByRole('button').filter({hasText: marker})
    await tile.click()
    await page.getByRole('button', {name: 'Przejmij', exact: true}).click()
    const list = async () => (await (await page.request.get('/api/v1/cases', {params: {search: author.externalChannelId}})).json()).items
    const caseId = (await list())[0].id
    const path = '/api/v1/cases/' + caseId
    const endpoint = path + '/internal-notes'
    const detail = async () => (await (await page.request.get(path)).json())
    const observed = async () => (await (await page.request.get('/__test/observed')).json()).messages
      .filter((delivery: {thread: string}) => delivery.thread === root.ts)
    await other.goto('/cases?caseId=' + caseId)
    await expect(other.getByText(marker, {exact: true})).toBeVisible()
    await expect(other.getByRole('button', {name: 'Internal note', exact: true})).toBeDisabled()
    expect((await command(other, endpoint, {body: 'Attempt by non-owner'})).status()).toBe(403)
    const anonymous = await browser.newContext({baseURL})
    try {
      expect((await anonymous.request.get(path + '/messages?includeInternalNotes=true')).status()).toBe(401)
    } finally {
      await anonymous.close()
    }

    const editor = page.getByRole('group', {name: 'Tryb edytora'})
    const publicMode = editor.getByRole('button', {name: 'Odpowiedź publiczna'})
    const noteMode = editor.getByRole('button', {name: 'Notatka wewnętrzna'})
    const publicDraft = page.getByRole('textbox', {name: 'Treść odpowiedzi'})
    const noteDraft = page.getByRole('textbox', {name: 'Treść notatki'})
    await expect(publicDraft).toBeEnabled()
    const before = await detail()
    const latestBefore = (await list())[0].lastMessageId
    await publicDraft.fill('Public draft retained independently')
    await page.getByLabel('Dodaj pliki').setInputFiles({name: 'public-only.txt', mimeType: 'text/plain', buffer: Buffer.from('Public attachment')})
    await noteMode.click()
    await expect(noteDraft).toBeEmpty()
    await expect(page.getByLabel('Dodaj pliki')).toHaveCount(0)
    await expect(page.getByRole('button', {name: 'Zapisz notatkę'})).toBeDisabled()
    const noteText = 'Internal text <b>shown literally</b>'
    await noteDraft.fill(noteText)
    await publicMode.click()
    await expect(publicDraft).toHaveValue('Public draft retained independently')
    await expect(page.getByRole('button', {name: 'public-only.txt', exact: true})).toBeVisible()
    await noteMode.click()
    await expect(noteDraft).toHaveValue(noteText)
    const saved = page.waitForResponse(response => response.url().endsWith('/internal-notes') && response.request().method() === 'POST')
    await page.getByRole('button', {name: 'Zapisz notatkę'}).click()
    const response = await saved
    expect(response.status()).toBe(201)
    const key = response.request().headers()['idempotency-key']
    const result = await response.json()
    const replay = await command(page, endpoint, {body: noteText}, 'POST', key)
    expect(replay.status()).toBe(201)
    expect(await replay.json()).toEqual(result)
    expect((await command(page, endpoint, {body: 'Different body'}, 'POST', key)).status()).toBe(409)
    const rendered = (tab: Page) => tab.locator('[data-message-kind="internal_note"]')
    for (const tab of [page, other]) {
      await expect(rendered(tab)).toHaveCount(1)
      await expect(rendered(tab).getByText(noteText, {exact: true})).toBeVisible()
      await expect(rendered(tab).locator('[data-message-author]')).toHaveText(author.displayName)
      await expect(rendered(tab).locator('time')).toHaveAttribute('datetime', /T/)
      await expect(rendered(tab).locator('b')).toHaveCount(0)
    }
    await expect(rendered(other)).toContainText('Internal note')
    const blended = await (await page.request.get(path + '/messages?includeInternalNotes=true&limit=1')).json()
    expect(blended.items).toEqual([expect.objectContaining({id: result.noteId, kind: 'INTERNAL_NOTE', deliveryStatus: null})])
    const pageTwo = await (await page.request.get(path + '/messages', {params: {
      includeInternalNotes: true, limit: 1, before: blended.nextCursor,
    }})).json()
    expect(pageTwo.items).toEqual([expect.objectContaining({id: latestBefore, kind: 'CUSTOMER'})])
    expect((await (await page.request.get(path + '/messages')).json()).items.map((item: {id: string}) => item.id)).toEqual([latestBefore])
    expect((await list())[0].lastMessageId).toBe(latestBefore)
    expect(await detail()).toMatchObject({status: before.status, version: before.version, waitingUntil: before.waitingUntil, sla: before.sla, responseCycles: before.responseCycles})
    expect(await observed()).toHaveLength(0)

    await publicMode.click()
    await expect(publicDraft).toHaveValue('Public draft retained independently')
    await page.getByRole('button', {name: 'public-only.txt', exact: true}).click()
    await page.getByRole('button', {name: 'Wyślij', exact: true}).click()
    await expect.poll(observed).toHaveLength(1)
    await expect.poll(async () => (await detail()).workflowStatus).toBe('WAITING_ON_CUSTOMER')
    const waiting = await detail()
    expect((await command(other, endpoint, {body: 'Non-owner attempt while waiting'})).status()).toBe(403)
    await noteMode.click()
    const waitingNote = 'Internal note while waiting for the customer'
    await noteDraft.fill(waitingNote)
    await page.getByRole('button', {name: 'Zapisz notatkę'}).click()
    await expect(rendered(page).getByText(waitingNote, {exact: true})).toBeVisible()
    expect(await detail()).toMatchObject({
      workflowStatus: 'WAITING_ON_CUSTOMER', owner: waiting.owner, waitingUntil: waiting.waitingUntil, version: waiting.version,
    })
    expect(await observed()).toHaveLength(1)
    await inbound(page, 'Customer follow-up after the public reply', root.ts)
    await expect.poll(async () => (await detail()).workflowStatus).toBe('OPEN')
    await page.goto('/cases?caseId=' + caseId)
    await page.reload()
    await expect(rendered(page)).toHaveCount(2)
    await expect(page.locator('[data-message-kind="support"]').getByText('Public draft retained independently')).toBeVisible()
    await selectLanguage(page, "English", "pl")
    const englishEditor = page.getByRole('group', {name: 'Composer mode'})
    await expect(englishEditor.getByRole('button', {name: 'Public reply'})).toBeVisible()
    const englishNoteMode = englishEditor.getByRole('button', {name: 'Internal note'})
    await englishNoteMode.click()
    await expect(page.getByRole('button', {name: 'Save note'})).toBeDisabled()
    expect((await command(page, path + '/resolve', {})).status()).toBe(200)
    await expect(englishNoteMode).toBeDisabled()
    expect((await command(page, endpoint, {body: 'Closed Case attempt'})).status()).toBe(409)
    await expect(rendered(page).getByText(noteText, {exact: true})).toBeVisible()
    await expect(rendered(page).getByText(waitingNote, {exact: true})).toBeVisible()
  } finally {
    await otherContext.close()
  }
})

test('responsive inbox shares quick-view predicates and preserves the actual reply author', async ({page}, testInfo) => {
  await page.setViewportSize({width: 1440, height: 960})
  const admin = await testAccount(page, 'account')
  const nextOwner = await testAccount(page)
  fixtures.set(page, admin)
  await login(page, admin.email)
  const marker = randomUUID().slice(0, 8)
  const url = 'https://example.test/support/' + 'long-token-'.repeat(45)
  const customerText = 'Diagnostics for an intermittent sign-in issue'
  const root = await inbound(page, customerText + '\n<' + url + '>', undefined, {
    user_profile: {display_name: 'Kasia Nowak'},
  })
  const caseRow = () => page.getByRole('button').filter({hasText: /#synthetic-support/}).filter({has: page.locator('[data-case-owner]')})
  const search = page.getByRole('textbox', {name: 'Szukaj rozmów'})
  await search.fill(admin.externalChannelId)
  const quick = (name: string) => page.getByRole('group', {name: 'Szybkie filtry'}).getByRole('button', {name, exact: true})
  const count = (view: string) => page.locator('[data-quick-view-count="' + view + '"]')
  await expect(count('all')).toHaveText('1')
  await expect(count('unassigned')).toHaveText('1')
  await expect(count('mine')).toHaveText('0')
  await expect(count('sla')).toHaveText('0')
  const listing = await (await page.request.get('/api/v1/cases', {params: {search: admin.externalChannelId, limit: 1}})).json()
  expect(listing.quickViewCounts).toEqual({all: 1, unassigned: 1, mine: 0, sla: 0})
  const caseId = listing.items[0].id
  const checkbox = page.getByRole('checkbox', {name: 'Zaznacz ' + listing.items[0].reference, exact: true})
  await quick('Nieprzypisane').click()
  const card = caseRow()
  await expect(card).toContainText('Kasia Nowak')
  const conversation = page.getByRole('region', {name: /^Rozmowa /})
  const queue = page.getByRole('region', {name: 'Lista czatów'})
  const details = page.getByRole('complementary', {name: 'Szczegóły sprawy'})
  const toggle = page.getByRole('button', {name: 'Szczegóły sprawy', exact: true})
  await card.click()
  await expect(conversation.locator('[data-message-author]')).toHaveText('Kasia Nowak')
  await expect(conversation.getByRole('link', {name: url, exact: true})).toBeVisible()
  const widths = await Promise.all([queue, conversation, details].map(async (panel) => (await panel.boundingBox())!.width))
  expect(widths[0]).toBeGreaterThanOrEqual(340)
  expect(widths[0]).toBeLessThanOrEqual(380)
  expect(widths[1]).toBeGreaterThanOrEqual(500)
  expect(widths[2]).toBeGreaterThanOrEqual(300)
  expect(widths[2]).toBeLessThanOrEqual(320)
  await details.getByRole('button', {name: 'Zwiń szczegóły'}).click()
  await expect(toggle).toBeFocused()
  await expect(details).toHaveCount(0)
  expect((await conversation.boundingBox())!.width).toBeGreaterThan(widths[1])
  await toggle.press('Enter')

  const fileId = 'F-inbound-inbox-layout-' + marker
  await inbound(page, undefined, root.ts, {files: [{id: fileId, name: 'diagnostics.txt'}], user_profile: {display_name: 'Kasia Nowak'}})
  await expect(conversation.getByRole('link', {name: new RegExp(fileId)})).toBeVisible()
  await expect(card).toContainText(/Plik: .*\.txt/)
  await conversation.getByRole('button', {name: 'Przejmij', exact: true}).click()
  await expect(count('unassigned')).toHaveText('0')
  await expect(count('mine')).toHaveText('1')
  await quick('Moje').click()
  await expect(card).toBeVisible()
  await conversation.getByRole('textbox', {name: 'Treść odpowiedzi'}).fill('Dziękuję, sprawdzam przesłane informacje.')
  await conversation.getByRole('button', {name: 'Wyślij', exact: true}).click()
  const reply = conversation.locator('[data-message-kind="support"]')
  await expect(reply.locator('[data-message-author]')).toHaveText(admin.displayName)
  const readStatus = async () => (await (await page.request.get('/api/v1/cases/' + caseId)).json()).workflowStatus
  await expect.poll(readStatus).toBe('WAITING_ON_CUSTOMER')
  await inbound(page, 'Customer follows up after delivery', root.ts)
  await expect.poll(readStatus).toBe('OPEN')
  expect((await command(page, '/api/v1/admin/cases/' + caseId + '/reassign', {targetUserId: nextOwner.userId})).status()).toBe(200)
  await expect(count('mine')).toHaveText('0')
  await expect(details.locator('[data-case-detail-owner]')).toHaveText(nextOwner.displayName)
  await expect(reply.locator('[data-message-author]')).toHaveText(admin.displayName)

  expect(await (await page.request.post('/__test/breach', {data: {caseId}})).json()).toEqual({changed: 1})
  await expect(count('sla')).toHaveText('1', {timeout: 20_000})
  await page.reload()
  await search.fill(admin.externalChannelId)
  await expect(count('all')).toHaveText('1')
  await expect(count('sla')).toHaveText('1')
  await quick('SLA').click()
  await expect(card).toBeVisible()
  await page.getByRole('button', {name: 'Operacje zbiorcze', exact: true}).click()
  await checkbox.check()
  await quick('Wszystkie').click()
  await expect(checkbox).not.toBeChecked()
  await page.getByRole('button', {name: 'Operacje zbiorcze', exact: true}).click()
  await page.getByRole('button', {name: 'Filtry', exact: true}).click()
  await page.getByLabel('Dostawca', {exact: true}).selectOption('teams')
  await expect(count('all')).toHaveText('0')
  await page.getByLabel('Dostawca', {exact: true}).selectOption('')
  await expect(count('all')).toHaveText('1')
  await page.getByRole('button', {name: 'Filtry', exact: true}).click()
  await card.click()
  await expect(reply.locator('[data-message-author]')).toHaveText(admin.displayName)
  const messages = conversation.locator('[data-message-kind]')
  expect(await messages.evaluateAll(rows => rows.every(row => row.getBoundingClientRect().x === rows[0].getBoundingClientRect().x && row.getBoundingClientRect().width <= 820))).toBe(true)
  await details.locator('summary').click()
  await expect(details.locator('li').first()).toBeVisible()
  await page.screenshot({path: testInfo.outputPath('inbox-desktop-1440.png'), animations: 'disabled'})

  await page.setViewportSize({width: 820, height: 900})
  await expect(queue).toBeVisible()
  await expect(conversation).toBeVisible()
  await expect(details).toHaveCount(0)
  await toggle.press('Enter')
  const drawer = page.getByRole('dialog', {name: 'Szczegóły sprawy'})
  await expect(drawer.locator('[data-case-detail-owner]')).toHaveText(nextOwner.displayName)
  await page.keyboard.press('Escape')
  await expect(drawer).not.toBeVisible()
  await expect(toggle).toBeFocused()

  await page.setViewportSize({width: 390, height: 844})
  await expect(queue).not.toBeVisible()
  await expect(conversation).toBeVisible()
  await expect(conversation.getByRole('link', {name: url, exact: true})).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  expect(await conversation.evaluate(element => element.scrollWidth)).toBe(390)
  await page.screenshot({path: testInfo.outputPath('inbox-mobile-390.png'), animations: 'disabled'})
  await toggle.press('Enter')
  await expect(drawer).toBeVisible()
  await page.screenshot({path: testInfo.outputPath('inbox-mobile-details-390.png'), animations: 'disabled'})
  await page.keyboard.press('Escape')
  await conversation.getByRole('button', {name: 'Wróć do listy'}).press('Enter')
  await expect(card).toBeFocused()
  await expect(conversation).not.toBeVisible()
  await page.screenshot({path: testInfo.outputPath('inbox-mobile-queue-390.png'), animations: 'disabled'})
  await card.press('Enter')
  await expect(conversation.getByRole('heading')).toBeFocused()
})

test('Case ownership follows Claim, Assign, Reassign and Unassign across two sessions and reconnect', async ({page, browser, baseURL}) => {
  const admin = await testAccount(page, 'account')
  const agent = await testAccount(page)
  fixtures.set(page, admin)
  const contextB = await browser.newContext({baseURL})
  const b = await contextB.newPage()
  let connected = 0
  let closed = 0
  b.on('websocket', socket => {
    socket.on('framereceived', frame => {
      if (String(frame.payload).startsWith('CONNECTED')) connected++
    })
    socket.on('close', () => closed++)
  })
  // Hold only the WebSocket offline so REST reads by the other session remain independent.
  await b.addInitScript(() => {
    const NativeSocket = window.WebSocket
    let disconnect: (() => void) | undefined
    window.WebSocket = class extends NativeSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols)
        if (new URL(url).pathname === '/ws') disconnect = () => this.close()
      }
    }
    Object.assign(window, {disconnectCaseSocket: () => disconnect?.()})
  })
  try {
    await Promise.all([login(page, admin.email), login(b, agent.email)])
    await expect.poll(() => connected).toBeGreaterThan(0)
    const marker = `Owner projection ${randomUUID().slice(0, 8)}`
    await inbound(page, marker)
    for (const tab of [page, b]) {
      const tile = tab.getByRole('button').filter({hasText: marker})
      await expect(tile).toContainText('#synthetic-support')
      await expect(tile.locator('[data-case-owner]')).toHaveText('Nieprzypisane')
      await tile.click()
    }
    const list = await (await page.request.get('/api/v1/cases')).json()
    const caseId = list.items.find((item: {lastMessagePreview: string}) => item.lastMessagePreview === marker).id
    const ownership = (tab: Page) => tab.getByRole('button').filter({hasText: marker}).locator('[data-case-owner]')
    const detail = (tab: Page) => tab.getByRole('complementary', {name: 'Szczegóły sprawy'})
    await b.getByRole('button', {name: 'Przejmij', exact: true}).click()
    for (const tab of [page, b]) {
      await expect(ownership(tab)).toHaveText(agent.displayName)
      await expect(detail(tab).locator('[data-case-detail-owner]')).toHaveText(agent.displayName)
    }
    await b.getByRole('button', {name: 'Moje', exact: true}).click()
    await expect(b.getByLabel('Liczba spraw')).toHaveText('1')
    await expect(b.locator('[data-quick-view-count="mine"]')).toHaveText('1')
    const adminPath = `/api/v1/admin/cases/${caseId}`
    await page.getByRole('button', {name: 'Administruj', exact: true}).click()
    const dialog = page.getByRole('dialog')
    const target = dialog.getByLabel('Agent')
    await expect(target.locator('option').filter({hasText: admin.displayName})).toHaveCount(1)
    await expect(target.locator('option').filter({hasText: agent.displayName})).toHaveCount(0)
    await target.selectOption(admin.userId)
    await dialog.getByRole('button', {name: 'Przypisz ponownie'}).click()
    await expect(b.getByLabel('Liczba spraw')).toHaveText('0')
    await expect(detail(b).locator('[data-case-detail-owner]')).toHaveText(admin.displayName)
    await expect(ownership(page)).toHaveText(admin.displayName)
    await b.getByRole('button', {name: 'Wszystkie', exact: true}).click()
    await expect(ownership(b)).toHaveText(admin.displayName)

    expect((await command(page, `${adminPath}/unassign`, undefined)).status()).toBe(200)
    await expect(ownership(b)).toHaveText('Nieprzypisane')
    expect((await command(page, `${adminPath}/assign`, {targetUserId: agent.userId})).status()).toBe(200)
    await expect(ownership(b)).toHaveText(agent.displayName)
    const before = await (await page.request.get(`/api/v1/cases/${caseId}`)).json()
    const unchanged = await command(page, `${adminPath}/reassign`, {targetUserId: agent.userId})
    expect(await unchanged.json()).toMatchObject({changed: false, version: before.version})
    const priorConnections = connected
    const beforeClose = closed
    await b.evaluate(() => (window as Window & {disconnectCaseSocket?: () => void}).disconnectCaseSocket?.())
    await expect.poll(() => closed).toBeGreaterThan(beforeClose)
    expect((await command(page, `${adminPath}/unassign`, undefined)).status()).toBe(200)
    await expect.poll(() => connected).toBeGreaterThan(priorConnections)
    await expect(ownership(b)).toHaveText('Nieprzypisane')
    await expect.poll(async () => {
      const expected = (await (await b.request.get('/api/v1/cases')).json()).quickViewCounts.unassigned
      return (await b.locator('[data-quick-view-count="unassigned"]').textContent()) === String(expected)
    }).toBe(true)
  } finally {
    await contextB.close()
  }
})

test('Snooze history is personal and timezone-aware while SLA follows workflow', async ({page, browser, baseURL}) => {
  const admin = await testAccount(page, 'account')
  fixtures.set(page, admin)
  const secondUser = await testAccount(page)
  const contexts = await Promise.all([
    browser.newContext({baseURL, timezoneId: 'Europe/Warsaw'}),
    browser.newContext({baseURL, timezoneId: 'America/New_York'}),
    browser.newContext({baseURL, timezoneId: 'Europe/Warsaw'}),
  ])
  const [a, mirror, other] = await Promise.all(contexts.map(context => context.newPage()))
  try {
    await login(a, admin.email)
    await login(mirror, admin.email, true)
    await login(other, secondUser.email)
    const marker = `Personal SLA ${randomUUID().slice(0, 8)}`
    const root = await inbound(page, marker)
    await expect(a.getByRole('button').filter({hasText: marker})).toBeVisible()
    const list = await (await a.request.get('/api/v1/cases')).json()
    const id = list.items.find((item: {lastMessagePreview: string}) => item.lastMessagePreview === marker).id
    const path = `/api/v1/cases/${id}`
    await Promise.all([a, mirror, other].map(tab => tab.goto(`/cases?caseId=${id}`)))
    const panel = (tab: Page) => tab.getByRole('region', {name: /^(Rozmowa|Conversation) /})
    const activity = async (tab: Page) => (await (await tab.request.get(`${path}/activity`)).json()).items
    const slaOf = async () => (await (await a.request.get(path)).json()).sla
    const slaBefore = await slaOf()
    await panel(a).getByRole('button', {name: 'Odłóż', exact: true}).click()
    await a.getByRole('dialog').getByRole('button', {name: '1h', exact: true}).click()
    await a.getByRole('dialog').getByRole('button', {name: 'Zatwierdź'}).click()
    const localTime = panel(a).locator('[data-active-snooze] time')
    const mirrorTime = panel(mirror).locator('[data-active-snooze] time')
    await expect(localTime).toContainText(/Odłożono do .* · (1 godz|[56][0-9] min)/)
    const deadline = (await localTime.getAttribute('datetime'))!
    await expect(mirrorTime).toHaveAttribute('datetime', deadline)
    const expected = await mirror.evaluate(value => new Intl.DateTimeFormat('en-GB', {
      dateStyle: 'short', timeStyle: 'short',
    }).format(new Date(value)), deadline)
    await expect(mirrorTime).toContainText(`Snoozed until ${expected}`)
    await expect(panel(other).locator('[data-active-snooze]')).toHaveCount(0)
    expect(await slaOf()).toEqual(slaBefore)
    expect(await activity(other)).toEqual([])
    await panel(a).getByRole('button', {name: 'Anuluj odłożenie'}).click()
    await expect(mirrorTime).toHaveCount(0)
    await expect.poll(async () => (await activity(a)).map((item: {action: string}) => item.action))
      .toEqual(['CASE_SNOOZE_CANCELLED', 'CASE_SNOOZE'])
    expect(await activity(other)).toEqual([])

    expect((await command(a, `${path}/claim`, undefined)).status()).toBe(200)
    expect((await command(a, `${path}/ask-customer`, {message: 'Please clarify', waitingMinutes: 60})).status()).toBe(202)
    await expect.poll(async () => (await (await a.request.get(path)).json()).status).toBe('WAITING_FOR_CUSTOMER')
    const details = (tab: Page) => tab.getByRole('complementary', {name: /^(Szczegóły sprawy|Case details)$/})
    await expect(details(a).locator('[data-sla-countdown]')).toContainText('Kontakt z klientem')
    const attention = async () => {
      const response=await a.request.get('/api/v1/cases?slaAttention=true')
      expect(response.status()).toBe(200)
      const body=await response.json()
      expect(Array.isArray(body.items)).toBe(true)
      return body.items as Array<{id:string}>
    }
    // Multiple open sessions and realtime invalidations cause concurrent list reads.
    // Every response must remain successful and preserve the same SLA predicate.
    for(const items of await Promise.all(Array.from({length:16},()=>attention()))) {
      expect(items.some(item=>item.id===id)).toBe(false)
    }

    expect((await command(a, `${path}/snooze`, {until: new Date(Date.now() + 60 * 60_000).toISOString()})).status()).toBe(200)
    await inbound(page, 'Customer response to the public reply', root.ts)
    await expect.poll(async () => (await activity(a)).map((item: {action: string}) => item.action))
      .toEqual(expect.arrayContaining(['CASE_CUSTOMER_REPLY', 'CASE_SNOOZE_WOKEN']))
    for (const [tab, text] of [[a, 'Klient odpowiedział'], [mirror, 'Customer replied']] as const) {
      await details(tab).locator('details > summary').click()
      await expect(details(tab).getByText(text, {exact: true})).toBeVisible()
    }
    expect((await activity(other)).map((item: {action: string}) => item.action)).toContain('CASE_CUSTOMER_REPLY')
    expect((await activity(other)).some((item: {action: string}) => item.action.startsWith('CASE_SNOOZE'))).toBe(false)
    expect((await activity(a)).find((item: {action: string}) => item.action === 'CASE_CUSTOMER_REPLY'))
      .toMatchObject({actorType: 'PROVIDER', actorLabel: 'Customer'})

    expect(await (await a.request.get(path)).json()).toMatchObject({
      workflowStatus: 'OPEN', owner: {id: admin.userId}, waitingUntil: null,
    })
    expect(await (await a.request.post('/__test/breach', {data: {caseId: id}})).json()).toEqual({changed: 1})
    await a.reload()
    const tile = a.locator('button[data-case-id="' + id + '"]')
    await expect(tile).toHaveClass(/border-red-500/)
    await a.getByRole('button', {name: 'SLA', exact: true}).click()
    await expect(tile).toBeVisible()
    const beforeCount = (await attention()).length
    await expect(a.getByLabel('Liczba spraw')).toHaveText(String(beforeCount))
    expect((await command(a, `${path}/resolve`, {})).status()).toBe(200)
    await expect(tile).toHaveCount(0)
    await expect(a.getByLabel('Liczba spraw')).toHaveText(String(beforeCount - 1))
    await a.getByRole('button', {name: 'Wszystkie', exact: true}).click()
    await expect(tile).not.toHaveClass(/border-red-500/)
    await expect(details(a).getByText('Historyczne przekroczenie SLA')).toBeVisible()
    expect((await slaOf()).state).toBe('BREACHED')
  } finally {
    await Promise.all(contexts.map(context => context.close()))
  }
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

test('Case composer uploads pasted PNG, retries scans and resumes partial Slack delivery', async ({page}) => {
  await login(page)
  const marker = randomUUID().slice(0, 8)
  const label = `Case attachments ${marker}`
  const root = await inbound(page, label)
  const option = page.getByRole('button', {name: new RegExp(label)})
  await option.click()
  const panel = page.getByRole('region', {name: /^Rozmowa /})
  await panel.getByRole('button', {name: 'Przejmij', exact: true}).click()
  const composer = panel.getByLabel('Treść odpowiedzi')
  const send = panel.getByRole('button', {name: 'Wyślij', exact: true})
  const picker = panel.getByLabel('Dodaj pliki')
  const fixture = fixtures.get(page)!
  await expect.poll(async () => (await (await page.request.get('/api/v1/cases')).json())
    .items.some((item: {lastMessagePreview: string}) => item.lastMessagePreview === label)).toBe(true)
  const cases = await (await page.request.get('/api/v1/cases')).json()
  const caseId = cases.items.find((item: {lastMessagePreview: string}) => item.lastMessagePreview === label).id
  const messagesPath = `/api/v1/cases/${caseId}/messages`
  const observed = async () => (await (await page.request.get('/__test/observed')).json()) as {
    messages: {clientMessageId: string; thread: string}[]
    files: {title: string; thread: string; channel: string}[]
  }
  const sendFromComposer = async () => {
    const result = page.waitForResponse(response => response.url().endsWith(messagesPath) && response.request().method() === 'POST')
    await send.click()
    const response = await result
    expect(response.status()).toBe(202)
    const {messageId} = await response.json()
    await expect.poll(async () => (await (await page.request.get(messagesPath)).json()).items
      .find((item: {id: string}) => item.id === messageId)?.deliveryStatus).toBe('SENT')
    return messageId as string
  }

  await expect(send).toBeDisabled()
  expect((await command(page, messagesPath, {body: '  ', attachmentIds: []})).status()).toBe(400)
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.evaluate(() => navigator.clipboard.writeText('Plain clipboard text'))
  await composer.press('ControlOrMeta+v')
  await expect(composer).toHaveValue('Plain clipboard text')
  await composer.clear()

  const screenshot = `screenshot-${marker}.png`
  await composer.evaluate((element, filename) => {
    const png = atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII=')
    const clipboard = new DataTransfer()
    clipboard.items.add(new File([Uint8Array.from(png, byte => byte.charCodeAt(0))], filename, {type: 'image/png'}))
    element.dispatchEvent(new ClipboardEvent('paste', {clipboardData: clipboard, bubbles: true, cancelable: true}))
  }, screenshot)
  const preview = panel.getByRole('button', {name: screenshot, exact: true})
  await expect(preview.locator('img')).toBeVisible()
  await expect(composer).toHaveValue('')
  const draftOnly = `remove-${marker}.txt`
  await picker.setInputFiles({name: draftOnly, mimeType: 'text/plain', buffer: Buffer.from('Discard me')})
  await panel.getByRole('button', {name: draftOnly, exact: true}).click()
  await expect(panel.getByRole('button', {name: draftOnly, exact: true})).toHaveCount(0)
  expect((await observed()).files.filter(file => file.thread === root.ts)).toHaveLength(0)
  const fileOnly = await sendFromComposer()
  await expect(panel.getByRole('link', {name: new RegExp(screenshot)})).toBeVisible()
  expect((await observed()).messages.filter(message => message.clientMessageId === fileOnly)).toHaveLength(0)
  await expect(panel.getByText('Oczekuje na klienta', {exact: true})).toBeVisible()
  await expect(composer).toBeEnabled()
  const casePath = `/api/v1/cases/${caseId}`
  const detail = async () => (await page.request.get(casePath)).json()
  const waiting = await detail()
  expect(waiting.waitingUntil).toEqual(expect.any(String))
  expect(waiting.owner.id).toBe((await (await page.request.get('/api/v1/auth/me')).json()).id)

  await picker.setInputFiles({name: 'oversized.txt', mimeType: 'text/plain', buffer: Buffer.alloc(25 * 1024 * 1024 + 1)})
  await expect(panel.getByRole('alert')).toContainText('Pojedynczy plik nie może przekraczać 25 MiB.')
  await expect(send).toBeDisabled()
  const tooLarge = await page.request.post(`/api/v1/cases/${caseId}/attachments`, {
    headers: {'X-XSRF-TOKEN': await csrf(page)},
    multipart: {file: {name: 'oversized.txt', mimeType: 'text/plain', buffer: Buffer.alloc(25 * 1024 * 1024 + 1)}},
  })
  expect(tooLarge.status()).toBe(413)
  expect(await tooLarge.json()).toMatchObject({code: 'ATTACHMENT_TOO_LARGE'})

  const scanName = `retry-${marker}.txt`
  const lostName = `lost-response-${marker}.txt`
  const badName = `blocked-${marker}.exe`
  await picker.setInputFiles({name: badName, mimeType: 'text/plain', buffer: Buffer.from('synthetic invalid extension')})
  await send.click()
  await expect(panel.getByRole('alert')).toContainText(`Typ pliku ${badName} jest niedozwolony.`)
  await panel.getByRole('button', {name: badName, exact: true}).click()
  await picker.setInputFiles([
    {name: scanName, mimeType: 'text/plain', buffer: Buffer.from(`test-only-scanner-offline-${marker}`)},
    {name: lostName, mimeType: 'text/plain', buffer: Buffer.from('Synthetic file with a lost completion response')},
  ])
  await composer.fill(`Text plus files ${marker}`)
  const initialUpload = page.waitForResponse(response => response.url().endsWith('/attachments') && response.request().method() === 'POST')
  await send.click()
  const upload = await initialUpload
  expect(upload.status()).toBe(201)
  const quarantined = await upload.json()
  expect(quarantined).toMatchObject({scanStatus: 'ERROR', scanError: 'SCANNER_UNAVAILABLE'})
  await expect(panel.getByRole('alert')).toContainText(`Nie udało się zeskanować pliku ${scanName}.`)
  expect((await page.request.get(`/api/v1/cases/${caseId}/attachments/${quarantined.attachmentId}`)).status()).toBe(409)
  expect((await command(page, messagesPath, {attachmentIds: [quarantined.attachmentId]})).status()).toBe(400)
  expect((await page.request.post(`/api/v1/cases/${caseId}/attachments/${quarantined.attachmentId}/scan`)).status()).toBe(403)
  const rescan = page.waitForResponse(response => response.url().endsWith(`/${quarantined.attachmentId}/scan`)
    && response.request().method() === 'POST')
  const combined = await sendFromComposer()
  expect(await (await rescan).json()).toMatchObject({attachmentId: quarantined.attachmentId, scanStatus: 'CLEAN'})
  await composer.fill(`Third consecutive reply ${marker}`)
  const textOnly = await sendFromComposer()
  const provider = await observed()
  expect(provider.messages.filter(message => [combined, textOnly].includes(message.clientMessageId))).toEqual([
    expect.objectContaining({clientMessageId: combined, thread: root.ts, channel: fixture.externalChannelId}),
    expect.objectContaining({clientMessageId: textOnly, thread: root.ts, channel: fixture.externalChannelId}),
  ])
  const received = provider.files.filter(file => file.thread === root.ts)
  expect(received.map(file => file.title).sort()).toEqual([screenshot, scanName, lostName].sort())
  expect(received.every(file => file.channel === fixture.externalChannelId)).toBe(true)
  const history = await (await page.request.get(messagesPath)).json()
  expect(history.items.find((item: {id: string}) => item.id === fileOnly)).toMatchObject({body: '', attachments: [{fileName: screenshot}]})
  expect(history.items.find((item: {id: string}) => item.id === combined).attachments)
    .toEqual(expect.arrayContaining([expect.objectContaining({id: quarantined.attachmentId})]))
  expect(await detail()).toMatchObject({
    workflowStatus: 'WAITING_ON_CUSTOMER', owner: waiting.owner,
    availableActions: expect.arrayContaining(['REPLY', 'RESOLVE']),
  })
  expect(Date.parse((await detail()).waitingUntil)).toBeGreaterThan(Date.parse(waiting.waitingUntil))
  await selectLanguage(page, 'English', 'pl')
  const englishPanel = page.getByRole('region', {name: /^Conversation /})
  await expect(englishPanel.getByText('ASKED', {exact: true})).toBeVisible()
  await expect(englishPanel.getByRole('button', {name: 'Ask customer', exact: true})).toHaveCount(0)
  await selectLanguage(page, 'Polski', 'en')
  await panel.getByRole('button', {name: 'Rozwiąż', exact: true}).click()
  await page.getByRole('dialog').getByRole('button', {name: 'Zatwierdź', exact: true}).click()
  await expect.poll(detail).toMatchObject({workflowStatus: 'RESOLVED', owner: waiting.owner, waitingUntil: null})
  await page.goto(`/cases?caseId=${caseId}`)
  await page.reload()
  await expect(panel.getByText(`Text plus files ${marker}`, {exact: true})).toBeVisible()
  await expect(panel.getByRole('link', {name: new RegExp(screenshot)})).toBeVisible()
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
  const accepted = page.waitForResponse(response => response.url().endsWith('/messages') && response.request().method() === 'POST')
  await page.getByRole('button', {name: 'Wyślij', exact: true}).click()
  const sent = await accepted
  expect(sent.status()).toBe(202)
  const caseId = new URL(sent.url()).pathname.split('/').at(-2)
  const archiveNotice = 'Kanał Slack jest zarchiwizowany. Nie można wysyłać do niego wiadomości.'
  await expect(page.locator('[data-message-kind="support"]').getByText(archiveNotice)).toBeVisible()
  await expect(page.locator('[data-sonner-toast]').getByText(archiveNotice)).toHaveCount(1)
  const profile = await (await page.request.get('/api/v1/auth/me')).json()
  expect(await (await page.request.get('/api/v1/cases/' + caseId)).json())
    .toMatchObject({status: 'VERIFICATION', owner: {id: profile.id}, waitingUntil: null})
  await page.goto(`/cases?caseId=${caseId}`)
  await page.reload()
  await expect(page.locator('[data-message-kind="support"]').getByText(archiveNotice)).toBeVisible()
  await expect(page.locator('[data-sonner-toast]').getByText(archiveNotice)).toHaveCount(0)
  await expect(page.getByRole('button', {name: 'Ponów wysyłkę'})).toHaveCount(0)
})

test('Slack rate limiting schedules a retry and only one message is delivered', async ({page}) => {
  await login(page)
  const marker = randomUUID().slice(0, 8)
  const root = await inbound(page, 'Rate limit scenario ' + marker)
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
  const casePath = new URL(response.url()).pathname.replace(/\/messages$/, '')
  const newer = 'Customer writes during provider retry ' + marker
  await inbound(page, newer, root.ts)
  await expect(page.getByText(newer, {exact: true})).toBeVisible()
  await expect(bubble.getByText(/Wysłano/)).toBeVisible()
  expect(await (await page.request.get(casePath)).json())
    .toMatchObject({workflowStatus: 'OPEN', waitingUntil: null})
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

type TestAccount = SlackFixture & { userId: string; email: string; displayName: string }

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

for (const visibility of ['public', 'private'] as const) {
  test('renders and delivers verified image and document formats through ' + visibility + ' Slack', async ({page, browser, baseURL}) => {
    test.setTimeout(90_000)
    const account = await (await page.request.post(visibility === 'public' ? '/__test/account' : '/__test/private-account')).json()
    const secondAccount = await testAccount(page)
    const secondContext = await browser.newContext({baseURL, viewport: {width: 390, height: 844}})
    const second = await secondContext.newPage()
    fixtures.set(page, account)
    try {
      await login(page, account.email)
      const marker = randomUUID().slice(0, 8)
      const root = await inbound(page, 'File format acceptance ' + marker)
      const cases = async () => (await (await page.request.get('/api/v1/cases', {params: {search: account.externalChannelId}})).json()).items
      await expect.poll(async () => (await cases()).length).toBe(1)
      const id = (await cases())[0].id
      const path = '/api/v1/cases/' + id
      const history = async () => (await (await page.request.get(path + '/messages')).json()).items
      const fixturesForTest = [
        ['png', 'image/png'], ['jpeg', 'image/jpeg'], ['gif', 'image/gif'], ['webp', 'image/webp'],
        ['avif', 'image/avif'], ['bmp', 'image/bmp'], ['pdf', 'application/pdf'],
        ['docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
        ['xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
        ['pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'],
      ] as const
      const inline = new Set(['png', 'jpeg', 'gif', 'webp', 'avif', 'bmp'])
      const data = await Promise.all(fixturesForTest.map(async ([extension, mimeType]) => ({
        extension, mimeType,
        buffer: await readFile(resolve(__dirname, '../../../api/src/test/resources/attachments/preview-fixtures/sample.' + extension)),
      })))
      await page.goto('/cases?caseId=' + id)
      const feed = page.getByRole('region', {name: /^Rozmowa /})
      const caption = 'Files from Slack ' + marker
      await inbound(page, caption, root.ts, {files: data.map(fixture => ({id: 'F-inbound-format-' + marker + '-' + fixture.extension}))})
      const inboundItems = async () => (await history()).find((item: {body: string}) => item.body === caption)?.attachments ?? []
      await expect.poll(async () => (await inboundItems()).map((item: {scanStatus: string}) => item.scanStatus),
        {timeout: 25_000}).toEqual(data.map(() => 'CLEAN'))
      for (const fixture of data) {
        const fileName = 'F-inbound-format-' + marker + '-' + fixture.extension + '.' + fixture.extension
        const item = (await inboundItems()).find((candidate: {fileName: string}) => candidate.fileName === fileName)
        expect(item.detectedContentType).toBe(fixture.mimeType)
        const url = path + '/attachments/' + item.id
        const download = await page.request.get(url)
        expect(download.headers()['content-disposition']).toMatch(/^attachment/)
        expect(download.headers()['cache-control']).toContain('no-store')
        expect(await download.body()).toEqual(fixture.buffer)
        const preview = await page.request.get(url + '/preview')
        expect(preview.status()).toBe(inline.has(fixture.extension) ? 200 : 404)
        if (inline.has(fixture.extension)) {
          expect(preview.headers()['content-type']).toBe(fixture.mimeType)
          expect(preview.headers()['x-content-type-options']).toBe('nosniff')
          expect(preview.headers()['cross-origin-resource-policy']).toBe('same-origin')
          expect(preview.headers()['cache-control']).toContain('no-store')
          expect(preview.headers()['content-disposition']).toMatch(/^inline/)
          const image = feed.getByRole('img', {name: fileName, exact: true})
          await image.scrollIntoViewIfNeeded()
          await expect.poll(() => image.evaluate(el => (el as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
        } else {
          const link = feed.getByRole('link', {name: new RegExp(fileName)})
          await expect(link).toContainText(fixture.extension.toUpperCase())
          await expect(link.locator('svg').first()).toBeVisible()
        }
      }
      const selected = (await inboundItems()).find((item: {detectedContentType: string}) => item.detectedContentType === 'image/png')
      const selectedUrl = path + '/attachments/' + selected.id
      await expect(feed.getByRole('img', {name: selected.fileName, exact: true})).toBeVisible()
      expect((await second.request.get(selectedUrl + '/preview')).status()).toBe(401)
      expect((await second.request.get(selectedUrl)).status()).toBe(401)
      expect((await page.request.get('/api/v1/cases/' + randomUUID() + '/attachments/' + selected.id + '/preview')).status()).toBe(404)

      await login(second, secondAccount.email, true)
      await second.goto('/cases?caseId=' + id)
      await second.reload()
      await second.getByRole('button', {name: 'Open preview for ' + selected.fileName}).click()
      const dialog = second.getByRole('dialog', {name: selected.fileName})
      await expect(dialog.getByRole('img', {name: selected.fileName})).toBeVisible()
      await expect(dialog.getByRole('link', {name: 'Download original'})).toHaveAttribute('href', selectedUrl)
      expect(await second.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
      await second.keyboard.press('Escape')
      await expect(dialog).toHaveCount(0)

      const infection = 'F-inbound-infected-' + marker
      await inbound(page, 'Quarantined batch', root.ts, {files: [{id: infection}, {id: 'F-inbound-missing-scope-' + marker}]})
      await expect(feed.getByText(/W pliku wykryto zagrożenie/)).toBeVisible()
      await expect(feed.getByText('Brakujące uprawnienie Slacka: files:read')).toBeVisible()
      const rejected = (await history()).find((item: {body: string}) => item.body === 'Quarantined batch').attachments
        .find((item: {scanStatus: string}) => item.scanStatus === 'INFECTED')
      for (const suffix of ['', '/preview']) {
        expect((await page.request.get(path + '/attachments/' + rejected.id + suffix)).status()).toBe(409)
      }
      await expect(feed.locator('[data-case-attachment="' + rejected.id + '"]').locator('img, a')).toHaveCount(0)
      await feed.getByRole('button', {name: 'Przejmij', exact: true}).click()
      const picker = feed.getByLabel('Dodaj pliki')
      const composer = feed.getByRole('textbox', {name: 'Treść odpowiedzi'})
      const send = feed.getByRole('button', {name: 'Wyślij', exact: true})
      const filenames = data.map(fixture => (fixture.extension === 'png' ? 'retry-' : 'out-') + marker + '.' + fixture.extension)
      await expect(picker).toBeEnabled()
      await picker.setInputFiles(data.map((fixture, index) => ({name: filenames[index], mimeType: fixture.mimeType, buffer: fixture.buffer})))
      await expect(feed.getByRole('button', {name: filenames.at(-1)!, exact: true})).toBeVisible()
      const outgoing = 'Files from USI ' + marker
      await composer.fill(outgoing)
      await send.click()
      await expect.poll(async () => (await history()).find((item: {body: string}) => item.body === outgoing)?.deliveryStatus,
        {timeout: 25_000}).toBe('SENT')
      const stored = (await history()).find((item: {body: string}) => item.body === outgoing)
      expect(stored.attachments.map((item: {detectedContentType: string}) => item.detectedContentType).sort())
        .toEqual(data.map(fixture => fixture.mimeType).sort())
      const provider = await (await page.request.get('/__test/observed')).json()
      expect(provider.files.filter((file: {thread: string}) => file.thread === root.ts).map((file: {title: string}) => file.title).sort())
        .toEqual([...filenames].sort())
      expect(provider.messages.filter((message: {clientMessageId: string; thread: string}) => message.clientMessageId === stored.id))
        .toEqual([expect.objectContaining({thread: root.ts})])
      await page.reload()
      await expect(feed.getByRole('img', {name: filenames[0], exact: true})).toBeVisible()
      await expect(feed.getByRole('link', {name: new RegExp(filenames.at(-1)!)})).toBeVisible()

      await inbound(page, 'Follow-up before permission check', root.ts)
      await expect(composer).toBeEnabled()
      await picker.setInputFiles({name: 'scope-required-' + marker + '.pdf', mimeType: 'application/pdf',
        buffer: data.find(item => item.extension === 'pdf')!.buffer})
      await send.click()
      await expect(feed.getByText('Brakujące uprawnienie Slacka: files:write')).toBeVisible()
    } finally {
      await secondContext.close()
    }
  })
}
