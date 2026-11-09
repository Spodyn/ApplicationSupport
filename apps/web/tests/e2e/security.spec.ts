import { expect, test } from '@playwright/test'

test('HTML uses a fresh nonce CSP, hydrates login and blocks injected scripts', async ({ page }) => {
  // Inject at the HTML response boundary so the browser parses the attack as
  // untrusted markup, rather than executing through DevTools' privileged context.
  await page.route('**/login', async route => {
    const response = await route.fetch()
    const body = (await response.text()).replace('</head>', '<script>window.__injectedSecurityProbe = true</script></head>')
    await route.fulfill({ response, body })
  })
  await page.route('**/api/**', route => route.fulfill({
    status: 401, contentType: 'application/problem+json', body: '{"code":"AUTHENTICATION_REQUIRED"}',
  }))
  const response = await page.goto('/login')
  const headers = response!.headers()
  const policy = headers['content-security-policy']
  expect(policy).toContain("frame-ancestors 'none'")
  expect(policy).toContain("'strict-dynamic'")
  expect(policy).not.toContain("'unsafe-eval'")
  expect(headers['x-frame-options']).toBe('DENY')
  expect(headers['x-content-type-options']).toBe('nosniff')
  expect(headers['referrer-policy']).toBe('no-referrer')
  expect(headers['cache-control']).toContain('no-store')
  await expect(page.getByRole('heading', { name: 'Zaloguj się' })).toBeVisible()
  const nonce = policy.match(/'nonce-([^']+)'/)?.[1]
  expect(nonce).toBeTruthy()
  expect(await page.locator('script').evaluateAll(scripts => scripts.filter(script => script.textContent && !script.textContent.includes('__injectedSecurityProbe')).every(script => Boolean((script as HTMLScriptElement).nonce)))).toBe(true)
  expect(await page.evaluate(() => '__injectedSecurityProbe' in window)).toBe(false)
  const second = await page.reload()
  expect(second!.headers()['content-security-policy']).not.toContain(`'nonce-${nonce}'`)
})

test('login without JavaScript submits credentials only in a POST body', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, baseURL })
  try {
    const page = await context.newPage()
    await page.route('**/login', route => route.request().method() === 'POST'
      ? route.fulfill({ status: 200, contentType: 'text/plain', body: 'Test form submission captured' })
      : route.continue())
    await page.goto('/login')
    await page.getByLabel('E-mail').fill('no-js@example.com')
    await page.getByLabel('Hasło').fill('test-only-no-js-credential')
    const submitted = page.waitForRequest(request => request.method() === 'POST')
    await page.getByRole('button', { name: 'Zaloguj się' }).click()
    const request = await submitted
    expect(new URL(request.url()).search).toBe('')
    const body = new URLSearchParams(request.postData()!)
    expect(body.get('email')).toBe('no-js@example.com')
    expect(body.get('password')).toBe('test-only-no-js-credential')
  } finally {
    await context.close()
  }
})
