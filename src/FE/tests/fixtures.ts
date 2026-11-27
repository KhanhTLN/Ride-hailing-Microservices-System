import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";
export const ids = {
  user: "10000000-0000-4000-8000-000000000001",
  driver: "20000000-0000-4000-8000-000000000001",
  trip: "30000000-0000-4000-8000-000000000001",
  quote: "40000000-0000-4000-8000-000000000001",
  operation: "50000000-0000-4000-8000-000000000001",
  attempt: "60000000-0000-4000-8000-000000000001",
  reservation: "70000000-0000-4000-8000-000000000001",
};
export const pickup = {
  place_id: "1",
  display_name: "Nhà hát Lớn, Tràng Tiền, Hà Nội",
  latitude: 21.0242,
  longitude: 105.8578,
};
export const dropoff = {
  place_id: "2",
  display_name: "Lăng Bác, Ba Đình, Hà Nội",
  latitude: 21.0369,
  longitude: 105.8347,
};
export async function fixture(
  page: Page,
  role: "PASSENGER" | "DRIVER" | "ADMIN" = "PASSENGER",
  options: {
    lostCancel?: boolean;
    expiredQuote?: boolean;
    noProfile?: boolean;
  } = {},
) {
  const user = {
    id: ids.user,
    email: "rider@example.test",
    display_name:
      role === "DRIVER"
        ? "Minh Tài Xế"
        : role === "ADMIN"
          ? "Quản trị ride"
          : "An Nguyễn",
    roles: [role],
    created_at: "2026-01-01T00:00:00Z",
  };
  const profile = {
    id: ids.driver,
    user_id: ids.user,
    license_number: "GPLX-TEST",
    account_status:
      role === "ADMIN" || options.noProfile ? "PENDING" : "APPROVED",
    accepting_trips: false,
    created_at: "2026-01-01T00:00:00Z",
  };
  let hasProfile = !options.noProfile;
  const vehicle = {
    id: "vehicle",
    driver_id: ids.driver,
    plate_number: "29TEST",
    vehicle_type: "MOTORBIKE",
    brand: "Honda",
    model: "Vision",
    color: "Trắng",
    seat_count: 2,
  };
  let hasVehicle = !options.noProfile;
  const state = {
    trip: null as any,
    creates: 0,
    quotes: 0,
    quoteKeys: [] as string[],
    cancels: [] as any[],
    locations: 0,
    registrations: [] as any[],
    failures: [] as string[],
  };
  page.on("pageerror", (error) =>
    state.failures.push(`browser: ${error.message}`),
  );
  const tripTemplate = {
    id: ids.trip,
    passenger_id: "passenger",
    quote_id: ids.quote,
    pickup: { ...pickup, address: pickup.display_name },
    dropoff: { ...dropoff, address: dropoff.display_name },
    vehicle_type: "MOTORBIKE",
    status: "SEARCHING",
    payment_status: "PENDING",
    estimated_fare: { amount: "32000", currency: "VND" },
    version: 1,
    driver_id: ids.driver,
    attempt_id: ids.attempt,
    reservation_id: ids.reservation,
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
  let operation: any = null,
    lost = false;
  await page.route("https://tile.openstreetmap.org/**", (route) =>
    route.abort(),
  );
  await page.route("**/backend/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname,
      method = request.method();
    const body = request.postData() ? request.postDataJSON() : {};
    const reply = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(data),
      });
    if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh"))
      return reply(
        body.password === "wrong"
          ? { code: "UNAUTHENTICATED" }
          : {
              access_token: "fixture-token",
              refresh_token: "fixture-refresh",
              user,
              expires_in: 900,
            },
        body.password === "wrong" ? 401 : 200,
      );
    if (path.endsWith("/auth/register")) {
      state.registrations.push(body);
      return reply(user, 201);
    }
    if (path.endsWith("/auth/logout")) return reply({ ok: true });
    if (path.endsWith("/maps/config"))
      return reply({
        tile_url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        motorbike_uses_driving_model: true,
      });
    if (path.endsWith("/maps/suggestions"))
      return reply([
        url.searchParams.get("q")!.includes("Lăng") ? dropoff : pickup,
      ]);
    if (path.endsWith("/maps/route"))
      return reply({
        distance_m: 4322,
        duration_s: 913,
        geometry: {
          type: "LineString",
          coordinates: [
            [pickup.longitude, pickup.latitude],
            [dropoff.longitude, dropoff.latitude],
          ],
        },
      });
    if (path.endsWith("/quotes")) {
      state.quotes++;
      state.quoteKeys.push(request.headers()["idempotency-key"]);
      return reply(
        {
          id: ids.quote,
          ...body,
          estimated_distance_m: 4322,
          estimated_duration_s: 913,
          fare: { amount: "32000", currency: "VND" },
          expires_at: new Date(
            Date.now() +
              (options.expiredQuote && state.quotes === 1 ? -1000 : 120000),
          ).toISOString(),
        },
        201,
      );
    }
    if (path.endsWith("/users/me")) return reply(user);
    if (path.endsWith(`/api/user/${ids.user}`)) {
      user.email = body.email;
      return reply({ data: user, success: true });
    }
    if (path.endsWith("/admin/drivers") && method === "GET")
      return reply([profile]);
    if (path.includes("/admin/drivers/") && method === "PATCH") {
      expect(body.reason).toBeTruthy();
      profile.account_status = body.account_status;
      return reply(profile);
    }
    if (path.endsWith("/drivers/me")) {
      if (method === "POST") {
        hasProfile = true;
        expect(body.license_number).toBeTruthy();
        return reply(profile, 201);
      }
      return reply(
        hasProfile ? profile : { code: "NOT_FOUND" },
        hasProfile ? 200 : 404,
      );
    }
    if (path.endsWith("/drivers/me/vehicle")) {
      if (method === "PUT") {
        hasVehicle = true;
        Object.assign(vehicle, body);
        return reply(vehicle);
      }
      return reply(hasVehicle ? vehicle : {}, hasVehicle ? 200 : 404);
    }
    if (path.endsWith("/availability")) {
      profile.accepting_trips = body.accepting_trips;
      return reply({ accepting_trips: body.accepting_trips });
    }
    if (path.endsWith("/location-sessions"))
      return reply({ session_id: "session-1" }, 201);
    if (path.endsWith("/heartbeat")) return reply({ ok: true });
    if (path.endsWith("/location")) {
      if (method === "GET") return reply({}, 404);
      state.locations++;
      expect(body.session_id).toBe("session-1");
      if (!state.trip)
        state.trip = { ...tripTemplate, status: "DRIVER_OFFERED" };
      return reply(body);
    }
    if (path.endsWith("/trips/me"))
      return reply({ items: state.trip ? [state.trip] : [] });
    if (path.endsWith("/trips") && method === "POST") {
      state.creates++;
      expect(body).toEqual({
        quote_id: ids.quote,
        payment_method: "SIMULATED",
      });
      expect(request.headers()["idempotency-key"]).toBeTruthy();
      state.trip = { ...tripTemplate };
      await new Promise((resolve) => setTimeout(resolve, 250));
      return reply(state.trip, 201);
    }
    if (path.endsWith(`/trips/${ids.trip}`) && method === "GET")
      return reply(state.trip);
    if (/\/trips\/[^/]+\/[^/]+$/.test(path) && method === "POST") {
      const action = path.split("/").at(-1)!;
      if (action === "cancel") {
        const record = { key: request.headers()["idempotency-key"], body };
        state.cancels.push(record);
        if (state.cancels.length > 1) expect(record).toEqual(state.cancels[0]);
      }
      if (!operation || operation.action !== action) {
        expect(body.expected_version).toBe(state.trip.version);
        if (["accept", "reject"].includes(action)) {
          expect(body.attempt_id).toBe(ids.attempt);
          expect(body.reservation_id).toBe(ids.reservation);
        }
        operation = {
          id: ids.operation,
          trip_id: ids.trip,
          status: "PENDING",
          action,
          type: action.toUpperCase(),
        };
      }
      if (action === "cancel" && options.lostCancel && !lost) {
        lost = true;
        return route.abort("connectionreset");
      }
      return reply(operation, 202);
    }
    if (path.includes("/operations/")) {
      if (operation.status === "PENDING") {
        operation.status = "SUCCEEDED";
        state.trip.version++;
        const transitions: Record<string, string> = {
          cancel: "CANCELLED",
          accept: "ACCEPTED",
          arrive: "ARRIVED",
          start: "IN_PROGRESS",
          complete: "COMPLETED",
        };
        state.trip.status = transitions[operation.action] || state.trip.status;
        if (operation.action === "complete")
          state.trip.final_fare = state.trip.estimated_fare;
      }
      return reply(operation);
    }
    state.failures.push(`${method} ${path}`);
    return reply({ message: "Unexpected fixture API" }, 500);
  });
  return { state, profile };
}
export async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Email", { exact: true }).fill("rider@example.test");
  await page
    .getByLabel("Mật khẩu", { exact: true })
    .fill("Correct-Password-12345");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(
    page.getByRole("navigation", { name: "Điều hướng chính" }),
  ).toBeVisible();
}
export async function addresses(page: Page) {
  await page.getByTestId("pickup").fill("Nhà hát");
  await page.getByRole("option").first().click();
  await page.getByTestId("dropoff").fill("Lăng Bác");
  await page.getByRole("option").first().click();
}
