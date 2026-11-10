import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.USI_TEST_PORT ?? 3190)
export default defineConfig({
  testDir: './tests/fullstack', outputDir: 'test-results/fullstack',
  globalTeardown: '../../scripts/testing/fullstack-teardown.mjs',
  fullyParallel: false, workers: 1, retries: 0, forbidOnly: Boolean(process.env.CI),
  timeout: 60_000, expect: {timeout: 15_000},
  use: {baseURL: `http://127.0.0.1:${port}`, serviceWorkers: 'allow', trace: 'retain-on-failure', screenshot: 'only-on-failure'},
  reporter: process.env.CI ? [['github'], ['html', {open: 'never'}]] : [['list']],
  projects: [{name: 'real-stack-chromium', use: {...devices['Desktop Chrome']}}],
  webServer: {
    command: 'node ../../scripts/testing/fullstack.mjs',
    url: `http://127.0.0.1:${port}/__test/ready`, reuseExistingServer: false,
    timeout: 240_000, stdout: 'pipe', stderr: 'pipe',
  },
})
