const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './src/test/browser',
  timeout: 30_000,
  workers: 1,
  reporter: 'line',
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
    { name: 'firefox', use: { browserName: 'firefox' } },
    { name: 'webkit', use: { browserName: 'webkit' } }
  ],
  use: { headless: true }
});
