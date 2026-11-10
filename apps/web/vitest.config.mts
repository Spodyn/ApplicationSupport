import react from "@vitejs/plugin-react"
import { defineConfig } from "vitest/config"

export default defineConfig({
  plugins: [react()],
  resolve: {
    tsconfigPaths: true,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/unit/setup.ts"],
    include: ["tests/unit/**/*.test.{ts,tsx}"],
    clearMocks: true,
    restoreMocks: true,
    coverage: {
      provider: 'v8', reporter: ['text-summary', 'json-summary', 'lcov'],
      include: ['lib/security/external-message.ts', 'lib/services/api/http-transport.ts',
        'lib/services/api/auth-adapter.ts', 'config/same-origin-policy.mjs', 'config/browser-security.mjs'],
      reportsDirectory: 'coverage',
      thresholds: {lines: 83, statements: 81, functions: 78, branches: 76},
    },
  },
})
