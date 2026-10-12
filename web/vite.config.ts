/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'

// Everything that isn't the app itself goes to the gateway, so the browser sees ONE origin:
// cookies stay first-party and the OAuth redirect comes back to this dev server.
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:8000'
const toGateway = { target: gateway, changeOrigin: false, xfwd: true }

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': toGateway,
      '/oauth2': toGateway,
      '/login/oauth2': toGateway,
      '/logout': toGateway,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
})
