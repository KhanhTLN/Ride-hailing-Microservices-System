'use strict';
const $ = id => document.getElementById(id);
const state = {points: {}, markers: {}, revision: 0, route: null, key: null, tokens: null, refresh: null, register: false, searches: {}, searchTimers: {}, searchVersions: {}, activeOptions: {}};
const suggestionCache = new Map();
let nextSuggestionAt = 0;
let map, config, line, expiryTimer;
function status(message, error = false) { $('status').textContent = message; $('status').classList.toggle('error', error); }
function authStatus(message, error = false) {
  $('auth-status').textContent = message;
  $('auth-status').hidden = !message;
  $('auth-status').classList.toggle('error', error);
}
function resetJourney() {
  state.revision++; state.route = null; state.key = null;
  $('journey').hidden = true; $('quote').hidden = true;
  if (line) { line.remove(); line = null; }
  clearInterval(expiryTimer);
  $('route-button').disabled = !state.points.pickup || !state.points.dropoff;
}
function body() { return {pickup: {...state.points.pickup}, dropoff: {...state.points.dropoff}, vehicle_type: $('vehicle').value}; }
async function request(url, options = {}, authenticated = true, retry = true) {
  const headers = {'Content-Type': 'application/json', 'X-Correlation-ID': crypto.randomUUID(), ...options.headers};
  if (authenticated) {
    if (!state.tokens) throw new Error('Vui lòng đăng nhập trước.');
    headers.Authorization = `Bearer ${state.tokens.access_token}`;
  }
  const response = await fetch(url, {...options, headers});
  if (response.status === 401 && authenticated && retry && state.tokens?.refresh_token) {
    if (!state.refresh) state.refresh = request(`${config.auth_base_url}/api/v1/auth/refresh`, {
      method: 'POST', body: JSON.stringify({refresh_token: state.tokens.refresh_token})
    }, false).then(tokens => { state.tokens = tokens; }).catch(error => { signedOut(); throw error; }).finally(() => { state.refresh = null; });
    await state.refresh;
    return request(url, options, authenticated, false);
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = response.status === 429 ? 'Dịch vụ đang giới hạn tốc độ. Vui lòng chờ 2 giây rồi thử lại.' : (data.message || `Yêu cầu thất bại (${response.status}).`);
    const error = new Error(message);
    error.status = response.status;
    error.retryAfterMs = Math.max(2000, Number(response.headers.get('Retry-After') || 2) * 1000);
    throw error;
  }
  return data;
}
function signedOut() {
  state.tokens = null;
  ['pickup', 'dropoff'].forEach(kind => { cancelSuggestions(kind); closeSuggestions(kind); });
  $('auth-form').hidden = false; $('logout').hidden = true;
  $('account-label').textContent = 'Đăng nhập để tìm đường và xem giá'; $('auth-panel').open = true;
  resetJourney();
}
function addressHint(kind, message, error = false) {
  $(`${kind}-hint`).textContent = message;
  $(`${kind}-hint`).classList.toggle('error', error);
}
function cancelSuggestions(kind) {
  clearTimeout(state.searchTimers[kind]);
  state.searches[kind]?.abort();
  state.searchVersions[kind] = (state.searchVersions[kind] || 0) + 1;
  $(kind).removeAttribute('aria-busy');
}
function closeSuggestions(kind) {
  $(`${kind}-results`).replaceChildren();
  $(`${kind}-results`).hidden = true;
  $(kind).setAttribute('aria-expanded', 'false');
  $(kind).removeAttribute('aria-activedescendant');
  state.activeOptions[kind] = -1;
}
function selectPoint(kind, point) {
  cancelSuggestions(kind); closeSuggestions(kind); resetJourney();
  const name = point.display_name;
  state.points[kind] = {latitude: point.latitude, longitude: point.longitude, address: name.slice(0, 300)};
  $(kind).value = name;
  addressHint(kind, kind === 'pickup' ? 'Đã chọn điểm đón.' : 'Đã chọn điểm đến.');
  state.markers[kind]?.remove();
  const icon = L.divIcon({className: `map-pin ${kind === 'dropoff' ? 'destination' : ''}`, html: kind === 'pickup' ? 'P' : 'D', iconSize: [32, 32], iconAnchor: [16, 16]});
  const popup = document.createElement('span'); popup.textContent = name;
  state.markers[kind] = L.marker([point.latitude, point.longitude], {icon}).addTo(map).bindPopup(popup);
  if (state.points.pickup && state.points.dropoff) {
    map.fitBounds(L.latLngBounds(Object.values(state.points).map(p => [p.latitude, p.longitude])), {padding: [35, 35], maxZoom: 16});
  } else map.setView([point.latitude, point.longitude], 15);
  $('route-button').disabled = !state.points.pickup || !state.points.dropoff;
  if (kind === 'pickup' && !state.points.dropoff) $('dropoff').focus();
}
function showSuggestions(kind, places) {
  closeSuggestions(kind);
  if (!places.length) { addressHint(kind, 'Không tìm thấy địa điểm. Hãy nhập thêm tên đường, quận hoặc thành phố.'); return; }
  places.forEach((place, index) => {
    const li = document.createElement('li'), button = document.createElement('button');
    li.setAttribute('role', 'presentation');
    button.type = 'button'; button.id = `${kind}-option-${index}`; button.setAttribute('role', 'option');
    button.setAttribute('aria-selected', 'false'); button.tabIndex = -1;
    const title = document.createElement('strong'), detail = document.createElement('span');
    const comma = place.display_name.indexOf(',');
    title.textContent = comma < 0 ? place.display_name : place.display_name.slice(0, comma);
    detail.textContent = comma < 0 ? '' : place.display_name.slice(comma + 1).trim();
    button.append(title, detail);
    button.addEventListener('click', () => selectPoint(kind, place));
    li.append(button); $(`${kind}-results`).append(li);
  });
  $(`${kind}-results`).hidden = false; $(kind).setAttribute('aria-expanded', 'true');
  addressHint(kind, `${places.length} địa điểm phù hợp. Chọn một địa điểm bên trên.`);
}
function scheduleSuggestions(kind, delay = 450) {
  cancelSuggestions(kind); closeSuggestions(kind);
  const query = $(kind).value.trim(), version = state.searchVersions[kind];
  if (query.length < 3) { addressHint(kind, 'Nhập ít nhất 3 ký tự để tìm địa điểm.'); return; }
  if (query.length > 200) { addressHint(kind, 'Địa chỉ tìm kiếm tối đa 200 ký tự.', true); return; }
  if (!state.tokens) { addressHint(kind, 'Đăng nhập để xem gợi ý địa điểm.'); return; }
  addressHint(kind, 'Đang tìm địa điểm…');
  state.searchTimers[kind] = setTimeout(() => fetchSuggestions(kind, query, version), delay);
}
async function fetchSuggestions(kind, query, version, retries = 0) {
  if (version !== state.searchVersions[kind] || !state.tokens) return;
  const key = query.toLocaleLowerCase().replace(/\s+/g, ' ');
  if (suggestionCache.has(key)) { showSuggestions(kind, suggestionCache.get(key)); return; }
  const wait = nextSuggestionAt - Date.now();
  if (wait > 0) {
    state.searchTimers[kind] = setTimeout(() => fetchSuggestions(kind, query, version, retries), wait);
    return;
  }
  nextSuggestionAt = Date.now() + 1200;
  const controller = new AbortController(); state.searches[kind] = controller;
  $(kind).setAttribute('aria-busy', 'true');
  try {
    const places = await request(`/api/v1/maps/suggestions?q=${encodeURIComponent(query)}`, {signal: controller.signal});
    if (controller.signal.aborted || version !== state.searchVersions[kind]) return;
    if (suggestionCache.size >= 50) suggestionCache.delete(suggestionCache.keys().next().value);
    suggestionCache.set(key, places); showSuggestions(kind, places);
  } catch (e) {
    if (controller.signal.aborted || version !== state.searchVersions[kind]) return;
    if (e.status === 429 && retries < 1) {
      nextSuggestionAt = Math.max(nextSuggestionAt, Date.now() + e.retryAfterMs);
      addressHint(kind, 'Đang chờ dịch vụ tìm địa điểm…');
      state.searchTimers[kind] = setTimeout(() => fetchSuggestions(kind, query, version, retries + 1), e.retryAfterMs);
    } else addressHint(kind, e instanceof TypeError ? 'Không kết nối được dịch vụ tìm địa điểm. Nhấn Enter để thử lại.' : e.message, true);
  } finally {
    if (version === state.searchVersions[kind]) $(kind).removeAttribute('aria-busy');
  }
}
document.querySelectorAll('.search-form').forEach(form => {
  const kind = form.dataset.point;
  $(kind).addEventListener('input', event => {
    delete state.points[kind]; state.markers[kind]?.remove(); delete state.markers[kind]; resetJourney();
    if (event.isComposing) { cancelSuggestions(kind); closeSuggestions(kind); return; }
    scheduleSuggestions(kind);
  });
  $(kind).addEventListener('compositionend', () => scheduleSuggestions(kind));
  form.addEventListener('submit', event => { event.preventDefault(); if (!state.points[kind]) scheduleSuggestions(kind, 0); });
  $(kind).addEventListener('keydown', event => {
    const options = [...$(`${kind}-results`).querySelectorAll('[role=option]')];
    if (event.key === 'Escape') { cancelSuggestions(kind); closeSuggestions(kind); return; }
    if (!options.length) return;
    if (event.key === 'Enter' && state.activeOptions[kind] >= 0) {
      event.preventDefault(); options[state.activeOptions[kind]].click(); return;
    }
    if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
    event.preventDefault();
    const active = state.activeOptions[kind];
    const index = active < 0 ? (event.key === 'ArrowDown' ? 0 : options.length - 1) : (active + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
    state.activeOptions[kind] = index;
    options.forEach((option, i) => option.setAttribute('aria-selected', String(i === index)));
    $(kind).setAttribute('aria-activedescendant', options[index].id);
    options[index].scrollIntoView({block: 'nearest'});
  });
});
$('vehicle').addEventListener('change', () => {
  resetJourney(); $('vehicle-note').hidden = $('vehicle').value !== 'MOTORBIKE' || !config.motorbike_uses_driving_model;
});
$('route-button').addEventListener('click', async () => {
  const revision = state.revision;
  $('route-button').disabled = true; status('Đang tìm tuyến đường…');
  try {
    const route = await request('/api/v1/maps/route', {method: 'POST', body: JSON.stringify(body())});
    if (revision !== state.revision) return;
    if (line) line.remove();
    line = L.geoJSON(route.geometry, {style: {color: '#177b55', weight: 6, opacity: .85}}).addTo(map);
    map.fitBounds(line.getBounds(), {padding: [35, 35], maxZoom: 16});
    state.route = route; $('journey').hidden = false;
    $('distance').textContent = `${(route.distance_m / 1000).toLocaleString('vi-VN', {maximumFractionDigits: 2})} km`;
    $('duration').textContent = `${Math.max(1, Math.ceil(route.duration_s / 60))} phút`;
    status('Đã tìm được tuyến đường.');
  } catch (e) { if (revision === state.revision) status(e.message, true); }
  finally { $('route-button').disabled = !state.points.pickup || !state.points.dropoff; }
});
$('quote-button').addEventListener('click', async () => {
  if (!state.route) return;
  const revision = state.revision;
  state.key ||= crypto.randomUUID();
  $('quote-button').disabled = true; status('Đang lấy giá…');
  try {
    const quote = await request('/api/v1/quotes', {method: 'POST', headers: {'Idempotency-Key': state.key}, body: JSON.stringify(body())});
    if (revision !== state.revision) return;
    $('quote').hidden = false; $('fare').textContent = new Intl.NumberFormat('vi-VN', {style: 'currency', currency: 'VND'}).format(Number(quote.fare.amount));
    $('distance').textContent = `${(quote.estimated_distance_m / 1000).toLocaleString('vi-VN', {maximumFractionDigits: 2})} km`;
    $('duration').textContent = `${Math.max(1, Math.ceil(quote.estimated_duration_s / 60))} phút`;
    const tick = () => {
      const seconds = Math.max(0, Math.ceil((Date.parse(quote.expires_at) - Date.now()) / 1000));
      $('expires').textContent = seconds ? `Giá có hiệu lực trong ${seconds} giây.` : 'Giá đã hết hạn. Lấy giá mới để tiếp tục.';
      if (!seconds) { clearInterval(expiryTimer); state.key = null; }
    };
    clearInterval(expiryTimer); tick(); expiryTimer = setInterval(tick, 1000); status('Đã nhận giá chuyến đi.');
  } catch (e) { if (revision === state.revision) status(e.message, true); }
  finally { $('quote-button').disabled = false; }
});
$('auth-mode').addEventListener('click', () => {
  state.register = !state.register;
  authStatus('');
  $('name-label').hidden = !state.register; $('display-name').required = state.register;
  $('auth-submit').textContent = state.register ? 'Tạo tài khoản' : 'Đăng nhập';
  $('auth-mode').textContent = state.register ? 'Đã có tài khoản' : 'Tạo tài khoản';
  $('password').autocomplete = state.register ? 'new-password' : 'current-password';
  $('password').minLength = state.register ? 12 : 1;
});
$('auth-form').addEventListener('invalid', event => {
  const messages = {email: 'Vui lòng nhập email hợp lệ.', password: state.register ? 'Mật khẩu đăng ký cần từ 12 đến 128 ký tự.' : 'Vui lòng nhập mật khẩu.', 'display-name': 'Vui lòng nhập tên hiển thị.'};
  authStatus(messages[event.target.id] || event.target.validationMessage, true);
}, true);
$('auth-form').addEventListener('submit', async event => {
  event.preventDefault(); $('auth-submit').disabled = true; $('auth-mode').disabled = true;
  authStatus(state.register ? 'Đang tạo tài khoản…' : 'Đang đăng nhập…');
  try {
    if (!config) throw new Error('Trang chưa kết nối được tới dịch vụ. Vui lòng tải lại trang.');
    const credentials = {email: $('email').value.trim(), password: $('password').value};
    if (state.register) await request(`${config.auth_base_url}/api/v1/auth/register`, {method: 'POST', headers: {'Idempotency-Key': crypto.randomUUID()}, body: JSON.stringify({...credentials, display_name: $('display-name').value.trim(), role: 'PASSENGER'})}, false);
    state.tokens = await request(`${config.auth_base_url}/api/v1/auth/login`, {method: 'POST', body: JSON.stringify(credentials)}, false);
    $('password').value = ''; $('auth-form').hidden = true; $('logout').hidden = false; $('auth-panel').open = false;
    $('account-label').textContent = state.tokens.user?.display_name || 'Đã đăng nhập'; status('Đã đăng nhập. Hãy chọn hành trình.');
    authStatus('Đăng nhập thành công. Bạn có thể chọn hành trình.');
    ['pickup', 'dropoff'].forEach(kind => { if (!state.points[kind] && $(kind).value.trim().length >= 3) scheduleSuggestions(kind); });
  } catch (e) {
    const message = e instanceof TypeError ? 'Không kết nối được User Service. Kiểm tra dịch vụ tại '+(config?.auth_base_url || 'cổng 8082')+'.' : e.message;
    authStatus(message, true);
  }
  finally { $('auth-submit').disabled = false; $('auth-mode').disabled = false; }
});
$('logout').addEventListener('click', async () => {
  try { if (state.tokens) await request(`${config.auth_base_url}/api/v1/auth/logout`, {method: 'POST', body: JSON.stringify({refresh_token: state.tokens.refresh_token})}, false); }
  catch (e) { authStatus(e.message, true); return; }
  signedOut(); authStatus('Đã đăng xuất.'); status('Đã đăng xuất.');
});
async function init() {
  try {
    config = await request('/maps/config', {}, false);
    config.auth_base_url = config.auth_base_url.replace(/\/+$/, '');
    map = L.map('map').setView([21.0285, 105.8542], 13);
    let tilesLoaded = 0;
    L.tileLayer(config.tile_url, {maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'})
      .on('loading', () => { tilesLoaded = 0; })
      .on('tileload', () => { tilesLoaded++; $('map-status').hidden = true; })
      .on('tileerror', () => {
        if (tilesLoaded) return;
        $('map-status').textContent = 'Không tải được ảnh nền OpenStreetMap. Kiểm tra kết nối Internet hoặc DNS trong trình duyệt. Bạn vẫn có thể tìm địa điểm và xem giá.';
        $('map-status').hidden = false;
      }).addTo(map);
    map.attributionControl.addAttribution('Routing: <a href="https://project-osrm.org/">OSRM</a>');
    $('vehicle-note').hidden = !config.motorbike_uses_driving_model;
  } catch (e) { status(`Không thể tải bản đồ: ${e.message}`, true); }
}
init();
