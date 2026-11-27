/* Run with Playwright installed separately; all provider/API calls are stubbed.
 * RHDS_PLAYWRIGHT_MODULE may point to an external playwright package.
 * RHDS_MAPS_URL defaults to the running Pricing Service's /maps page.
 */
const {chromium} = require(process.env.RHDS_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
async function main() {
  const browser = await chromium.launch(process.env.RHDS_BROWSER_PATH ? {executablePath: process.env.RHDS_BROWSER_PATH, headless: true} : {channel: 'msedge', headless: true});
  try {
    const page = await browser.newPage();
    const errors = [], calls = {suggestions: 0, route: 0, quote: 0, nominatim: 0, tiles: 0};
    page.on('pageerror', error => errors.push(error.message));
    const reply = (route, body, status = 200) => route.fulfill({status, headers: {'Access-Control-Allow-Origin': '*'}, contentType: 'application/json', body: JSON.stringify(body)});
    const places = [0, 1, 2].map(i => ({place_id: String(i), display_name: `Public landmark ${i}, Hanoi`, latitude: 21.0285 + i * .001, longitude: 105.8542}));
    const destinations = [0, 1].map(i => ({place_id: String(i + 10), display_name: `Destination ${i}, Hanoi`, latitude: 21.0385 + i * .001, longitude: 105.8642}));
    await page.route('https://tile.openstreetmap.org/**', route => { calls.tiles++; return route.abort(); });
    await page.route('**/maps/config', route => reply(route, {auth_base_url: 'http://localhost:8082', tile_url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', motorbike_uses_driving_model: true}));
    await page.route('**/api/v1/auth/**', route => {
      const password = route.request().postDataJSON()?.password;
      if (password === 'wrong') return reply(route, {message: 'Invalid email or password'}, 401);
      if (password === 'offline') return route.abort('failed');
      return reply(route, {access_token: 'test-access', refresh_token: 'test-refresh', user: {display_name: 'Map test'}});
    });
    await page.route(/\/api\/v1\/maps\/(places|reverse)\?/, route => { calls.nominatim++; return reply(route, {}, 500); });
    let limitedAttempts = 0;
    await page.route('**/api/v1/maps/suggestions?*', async route => {
      calls.suggestions++;
      const query = new URL(route.request().url()).searchParams.get('q');
      if (query === 'No matching place') return reply(route, []);
      if (query === 'Limited destination' && !limitedAttempts++) return reply(route, {message: 'Rate limited'}, 429);
      if (query === 'Older query') {
        await page.waitForTimeout(2200);
        return reply(route, [{...places[0], display_name: 'Stale result'}]).catch(() => {});
      }
      return reply(route, query === 'Public landmark' ? places : destinations);
    });
    await page.route('**/api/v1/maps/route', route => {
      calls.route++;
      const body = route.request().postDataJSON();
      assert.ok(body.pickup && body.dropoff); assert.equal(route.request().headers().authorization, 'Bearer test-access');
      assert.equal(body.pickup.latitude, places[1].latitude, 'Coordinates must come from the selected second result');
      assert.equal(body.dropoff.latitude, destinations[1].latitude, 'Keyboard selection must pick the active result');
      return reply(route, {distance_m: 4322, duration_s: 913, geometry: {type: 'LineString', coordinates: [[105.8542, 21.0285], [105.8642, 21.0385]]}, provider: 'OSRM', profile: 'driving', vehicle_model: 'driving'});
    });
    let quoteKey;
    await page.route('**/api/v1/quotes', route => {
      calls.quote++;
      const key = route.request().headers()['idempotency-key'];
      assert.ok(key); if (quoteKey) assert.equal(key, quoteKey); quoteKey = key;
      return reply(route, {estimated_distance_m: 4322, estimated_duration_s: 913, fare: {amount: '32000', currency: 'VND'}, expires_at: new Date(Date.now() + 120000).toISOString()}, 201);
    });
    await page.goto(process.env.RHDS_MAPS_URL || 'http://localhost:8083/maps', {waitUntil: 'networkidle'});
    await page.waitForSelector('.leaflet-pane', {state: 'attached'});
    assert.equal(await page.locator('#route-button').isDisabled(), true);
    await page.waitForSelector('#map-status:visible');
    assert.match(await page.locator('#map-status').innerText(), /OpenStreetMap/);
    await page.locator('#auth-submit').click();
    assert.equal(await page.locator('#auth-status').isVisible(), true, 'Missing credentials show beside the login form');
    await page.locator('#email').fill('maps@example.test'); await page.locator('#password').fill('wrong');
    await page.locator('#auth-submit').click();
    await page.waitForFunction(() => document.getElementById('auth-status').textContent === 'Invalid email or password');
    assert.equal(await page.locator('#auth-status').isVisible(), true);
    const authBox = await page.locator('#auth-status').boundingBox();
    assert.ok(authBox.y + authBox.height <= page.viewportSize().height, 'Login error is in the visible part of the sidebar');
    await page.locator('#password').fill('offline'); await page.locator('#auth-submit').click();
    await page.waitForFunction(() => document.getElementById('auth-status').textContent.includes('User Service'));
    await page.locator('#password').fill('MapPassword-12345');
    await page.locator('#auth-submit').click(); await page.waitForSelector('#logout:visible');
    assert.equal(await page.locator('#auth-status').isVisible(), true);
    await page.locator('#pickup').fill('Ha');
    await page.waitForTimeout(600); assert.equal(calls.suggestions, 0, 'Too-short queries do not call a provider');
    await page.locator('#pickup').fill('Public');
    await page.locator('#pickup').pressSequentially(' landmark', {delay: 10});
    await page.waitForSelector('#pickup-results button');
    assert.equal(calls.suggestions, 1, 'Debounce sends only the completed query');
    assert.equal(await page.locator('#pickup-results button').count(), 3);
    assert.equal(await page.locator('#route-button').isDisabled(), true, 'Typed text is not a selected coordinate');
    await page.locator('#pickup-results button').nth(1).click();
    assert.equal(await page.locator('#pickup').inputValue(), places[1].display_name);
    assert.equal(await page.locator('#pickup').getAttribute('aria-expanded'), 'false');
    await page.locator('#dropoff').fill('Destination');
    await page.waitForSelector('#dropoff-results button');
    assert.equal(await page.locator('#dropoff-results button').count(), 2);
    await page.locator('#dropoff').press('ArrowDown'); await page.locator('#dropoff').press('ArrowDown'); await page.locator('#dropoff').press('Enter');
    assert.equal(await page.locator('#dropoff').inputValue(), destinations[1].display_name);
    assert.equal(await page.locator('#route-button').isDisabled(), false);
    assert.equal(await page.locator('#vehicle-note').isVisible(), true);
    await page.locator('#route-button').click(); await page.waitForSelector('#journey:visible');
    assert.match(await page.locator('#distance').innerText(), /4,32/);
    assert.equal(await page.locator('#duration').innerText(), '16 ph\u00fat');
    await page.locator('#quote-button').click(); await page.waitForSelector('#quote:visible');
    assert.match(await page.locator('#fare').innerText(), /32\.000/);
    await page.locator('#quote-button').click(); await page.waitForTimeout(100); assert.equal(calls.quote, 2);
    await page.locator('#vehicle').selectOption('CAR'); assert.equal(await page.locator('#vehicle-note').isVisible(), false);
    assert.equal(await page.locator('#journey').isVisible(), false);
    await page.locator('#dropoff').fill('Ha'); assert.equal(await page.locator('#route-button').isDisabled(), true);
    assert.equal(await page.locator('.leaflet-marker-icon').count(), 1);
    assert.equal(await page.locator('.leaflet-marker-draggable').count(), 0, 'Markers display the selected addresses');
    await page.locator('#map').click({position: {x: 250, y: 250}});
    assert.equal(await page.locator('#route-button').isDisabled(), true, 'Map clicks must not select addresses');
    const olderRequest = page.waitForRequest(request => request.url().includes('/suggestions?') && new URL(request.url()).searchParams.get('q') === 'Older query');
    await page.locator('#dropoff').fill('Older query'); await olderRequest;
    await page.locator('#dropoff').fill('New destination');
    await page.waitForSelector('#dropoff-results button');
    await page.waitForTimeout(2300);
    assert.equal(await page.locator('#dropoff-results').innerText().then(text => text.includes('Stale result')), false, 'Stale responses never overwrite the newest query');
    await page.locator('#dropoff').press('Escape');
    assert.equal(await page.locator('#dropoff-results').isVisible(), false);
    await page.locator('#dropoff').fill('No matching place');
    await page.waitForFunction(() => document.getElementById('dropoff-hint').textContent.startsWith('Kh\u00f4ng t\u00ecm'));
    assert.equal(await page.locator('#dropoff-results').isVisible(), false);
    await page.locator('#dropoff').fill('Limited destination');
    await page.waitForSelector('#dropoff-results button');
    assert.equal(limitedAttempts, 2, 'A rate limit retries the current query once');
    await page.locator('#dropoff-results button').first().click();
    assert.equal(await page.locator('#route-button').isDisabled(), false);
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'Mobile view has no horizontal overflow');
    await page.locator('#logout').click(); await page.waitForSelector('#auth-form:visible');
    assert.equal(calls.nominatim, 0, 'Autocomplete never sends requests to public Nominatim');
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log(JSON.stringify({result: 'PASS', checks: ['map assets/base URL', 'tile failure notice', 'required login fields', 'visible invalid-password error', 'visible network error', 'login/logout', 'minimum query length', 'debounced autocomplete', 'multiple results', 'mouse/keyboard selection', 'selected coordinates in route', 'GeoJSON route', 'distance/ETA', 'quote/idempotency', 'stale response rejection', 'no-results state', 'rate-limit retry', 'input invalidation', 'map is display-only', 'no Nominatim autocomplete', 'vehicle warning', 'mobile layout', 'no browser errors'], calls, publicTileRequestsSent: 0}, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
