import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests against the REAL stack (Keycloak, gateway, core-service): start it first with
 *   docker compose --profile gateway --profile core up -d && npm run dev
 * then `npm run e2e`. WEB_URL points elsewhere; PW_CHROMIUM_PATH uses a preinstalled Chromium.
 */
const executablePath = process.env.PW_CHROMIUM_PATH
export default defineConfig({
  testDir: './e2e',
  // Not tests: scripts/clean-test-data.sh and scripts/seed-sample-data.sh run these on purpose.
  testIgnore: [...(process.env.CLEANUP ? [] : ['**/cleanup.spec.ts']), ...(process.env.SEED ? [] : ['**/seed.spec.ts'])],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  use: {
    baseURL: process.env.WEB_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    launchOptions: executablePath ? { executablePath } : {},
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 960 } }, grepInvert: /@phone/ },
    { name: 'phone', use: { ...devices['Pixel 7'] }, grep: /@phone/ },
  ],
})
