import { test, expect } from './catalog-fixture'

// HTTP fixtures check presentation and navigation, never deployed acceptance.
for (const width of [1440, 900, 390]) {
  test(`all catalog surfaces fit and retain readable controls at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.route('**/api/health', route => route.fulfill({ json: { status: 'ok', database: 'ok' } }))
    await page.route('**/api/v1/**', route => route.fulfill({ status: 503, json: { message: 'Fixture service unavailable' } }))
    for (const [route, heading, capture] of [
      ['/', 'Overview', 'overview'], ['/products', 'Products', 'products'],
      ['/formulas', 'Formula versions', 'formulas'], ['/materials', 'Materials & specs', 'materials'],
      ['/suppliers', 'Suppliers', 'suppliers'], ['/labels', 'Labels', 'labels'],
      ['/impact', 'Sign in required', 'impact'], ['/reviews', 'Sign in required', 'reviews'],
      ['/health', 'System status', 'health'],
    ]) {
      await page.goto(route)
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
      if (!['/impact', '/reviews', '/health'].includes(route)) {
        await expect(page.getByText('Connected to the M1 read-only API', { exact: false })).toBeVisible()
      }
      if (route === '/health') await expect(page.getByText('Healthy')).toHaveCount(2)
      if (route === '/labels') await expect(page.getByRole('alert')).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
      const controls = page.locator('main [data-slot=button]')
      for (const control of await controls.all()) {
        if (await control.isVisible()) expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44)
      }
      await page.screenshot({ path: `test-results/ui-polish/${capture}-${width}.png`, fullPage: true })
    }
    await page.goto('/products')
    await expect(page.getByRole('region', { name: 'Product records' })).toBeVisible()
    await page.getByRole('region', { name: 'Product records' }).focus()
    await expect(page.getByRole('region', { name: 'Product records' })).toBeFocused()
    if (width === 390) {
      await expect(page.getByRole('navigation', { name: 'Main navigation' })).not.toBeVisible()
      await page.getByRole('button', { name: 'Open navigation' }).click()
      await expect(page.getByRole('link', { name: 'Products', exact: true })).toBeVisible()
      await expect(page.locator('.sidebar')).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)')
      await page.screenshot({ path: 'test-results/ui-polish/navigation-390.png', fullPage: true })
      await page.getByRole('button', { name: 'Close navigation', exact: true }).click()
      await expect(page.getByRole('navigation', { name: 'Main navigation' })).not.toBeVisible()
    }
  })
}
