/** Opt-in local integration. Creates isolated users, bootstraps ONLY its own
 * admin through SQL, and drives the real browser UI + five backend services.
 * No tile downloads and no synthetic Matching/Payment events.
 */
import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { writeFileSync } from "node:fs";
import path from "node:path";
test("LIVE: register driver/passenger → admin approval → matching → complete", async ({
  browser,
  baseURL,
}, testInfo) => {
  test.skip(
    !process.env.FE_LIVE_TEST,
    "Set FE_LIVE_TEST=1 for the real local stack.",
  );
  test.setTimeout(180000);
  const suffix = randomUUID().slice(0, 8),
    password = `RideLive-${randomUUID()}!`,
    license = `FE-${suffix}`;
  const accounts = {
    driver: `fe-driver-${suffix}@example.test`,
    passenger: `fe-rider-${suffix}@example.test`,
    admin: `fe-admin-${suffix}@example.test`,
  };
  const contexts = await Promise.all([
    browser.newContext({
      baseURL,
      permissions: ["geolocation"],
      geolocation: { latitude: 21.0242, longitude: 105.8578 },
    }),
    browser.newContext({ baseURL }),
    browser.newContext({ baseURL }),
  ]);
  const [driver, passenger, admin] = await Promise.all(
    contexts.map((context) => context.newPage()),
  );
  const report: Record<string, unknown> = {
    run: suffix,
    scope:
      "real FE UI, User/Driver/Pricing/Trip/Matching; SQL is only the admin bootstrap fixture",
    checks: [] as string[],
  };
  let adminId = "",
    tripId = "";
  const composePath = path.resolve(
    process.cwd(),
    "../../deploy/docker-compose.yml",
  );
  function adminRole(role: "ADMIN" | "PASSENGER") {
    if (!/^[0-9a-f-]{36}$/i.test(adminId))
      throw new Error("Invalid fixture user id");
    execFileSync(
      "docker",
      [
        "compose",
        "-f",
        composePath,
        "exec",
        "-T",
        "user-db",
        "psql",
        "-X",
        "-q",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "postgres",
        "-d",
        "rhds_user",
        "-c",
        `UPDATE public."user" SET role='${role}' WHERE id='${adminId}'`,
      ],
      { stdio: "pipe" },
    );
  }
  async function register(
    page: Page,
    email: string,
    name: string,
    isDriver = false,
  ) {
    await page.goto("/");
    await page
      .getByRole("button", { name: "Tạo tài khoản", exact: true })
      .click();
    if (isDriver)
      await page.getByRole("button", { name: "Tôi là tài xế" }).click();
    await page.getByLabel("Tên hiển thị").fill(name);
    await page.getByLabel("Email", { exact: true }).fill(email);
    await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
    await page
      .getByRole("button", { name: "Tạo tài khoản", exact: true })
      .click();
    await expect(
      page.getByRole("navigation", { name: "Điều hướng chính" }),
    ).toBeVisible();
  }
  try {
    for (const context of contexts)
      await context.route("https://tile.openstreetmap.org/**", (route) =>
        route.abort(),
      );
    const created = await contexts[2].request.post(
      "/backend/user/api/v1/auth/register",
      {
        headers: {
          "Idempotency-Key": randomUUID(),
          "X-Correlation-ID": randomUUID(),
        },
        data: {
          email: accounts.admin,
          password,
          display_name: `FE Admin ${suffix}`,
          role: "PASSENGER",
        },
      },
    );
    expect(created.status()).toBe(201);
    adminId = (await created.json()).id;
    adminRole("ADMIN");
    await admin.goto("/");
    await admin.getByLabel("Email", { exact: true }).fill(accounts.admin);
    await admin.getByLabel("Mật khẩu", { exact: true }).fill(password);
    await admin.getByRole("button", { name: "Đăng nhập", exact: true }).click();
    await expect(
      admin.getByRole("heading", { name: "Hồ sơ tài xế", exact: true }),
    ).toBeVisible();
    await register(driver, accounts.driver, `FE Driver ${suffix}`, true);
    await driver.getByLabel("Số giấy phép lái xe").fill(license);
    await driver.getByRole("button", { name: "Gửi hồ sơ tài xế" }).click();
    await driver.getByLabel("Biển số").fill(`FE-${suffix}`);
    await driver.getByLabel("Hãng xe").fill("Honda");
    await driver.getByLabel("Dòng xe").fill("Vision");
    await driver.getByLabel("Màu sắc").fill("Trắng");
    await driver
      .getByRole("button", { name: "Lưu phương tiện", exact: true })
      .click();
    await expect(
      driver.getByText("Honda Vision", { exact: true }),
    ).toBeVisible();
    await admin.getByRole("textbox", { name: "Tìm tài xế" }).fill(license);
    await admin.getByRole("button", { name: "Xét duyệt", exact: true }).click();
    await admin
      .getByLabel("Lý do")
      .fill("Hồ sơ kiểm thử FE local, đã đối chiếu.");
    await admin.getByRole("button", { name: "Xác nhận", exact: true }).click();
    await expect(admin.getByRole("dialog")).not.toBeVisible();
    (report.checks as string[]).push(
      "driver registration + vehicle + real admin approval",
    );
    await register(passenger, accounts.passenger, `FE Rider ${suffix}`);
    await passenger.getByTestId("pickup").fill("Nhà hát Lớn Hà Nội");
    await passenger.getByRole("option").first().click();
    await passenger.getByTestId("dropoff").fill("Lăng Chủ tịch Hồ Chí Minh");
    await passenger.getByRole("option").first().click();
    await passenger
      .getByRole("button", { name: "Xem giá chuyến đi", exact: true })
      .click();
    await expect(passenger.getByTestId("quote-fare")).toBeVisible({
      timeout: 25000,
    });
    report.fare = await passenger.getByTestId("quote-fare").innerText();
    await expect(
      driver.getByRole("button", { name: "Bật nhận chuyến", exact: true }),
    ).toBeEnabled();
    await driver
      .getByRole("button", { name: "Bật nhận chuyến", exact: true })
      .click();
    await expect(
      driver.getByRole("heading", { name: "Bạn đang trực tuyến" }),
    ).toBeVisible();
    const oldBooking = passenger.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/trips") &&
        response.request().method() === "POST",
    );
    await passenger
      .getByRole("button", { name: "Đặt chuyến ngay", exact: true })
      .click();
    await expect(passenger).toHaveURL(/\/trips\/[0-9a-f-]{36}/);
    tripId = new URL(passenger.url()).hash.split("/")[2];
    const originalTrip = await (await oldBooking).json();
    report.cancelled_trip_id = tripId;
    await driver
      .getByRole("button", { name: "Nhận chuyến", exact: true })
      .click({ timeout: 20000 });
    await expect(
      driver.getByRole("heading", { name: "Tài xế đang đến", exact: true }),
    ).toBeVisible();
    await expect(
      passenger.getByRole("heading", { name: "Tài xế đang đến", exact: true }),
    ).toBeVisible();
    (report.checks as string[]).push(
      "real quote + create trip + automatic Trip → Matching → Driver + confirm reservation",
    );
    await driver
      .getByRole("button", { name: "Hủy chuyến", exact: true })
      .click();
    await driver
      .getByLabel("Lý do hủy chuyến")
      .fill("Tài xế cần hủy để kiểm tra đặt lại hành trình");
    await driver
      .getByRole("button", { name: "Xác nhận hủy", exact: true })
      .click();
    await expect(
      passenger.getByRole("heading", { name: "Đã hủy", exact: true }),
    ).toBeVisible();
    await passenger.getByRole("link", { name: "Đặt lại hành trình" }).click();
    await expect(passenger.getByTestId("pickup")).toHaveValue(
      originalTrip.pickup.address,
    );
    await expect(passenger.getByTestId("dropoff")).toHaveValue(
      originalTrip.dropoff.address,
    );
    await passenger
      .getByRole("button", { name: "Xem giá chuyến đi", exact: true })
      .click();
    report.fare = await passenger
      .getByTestId("quote-fare")
      .innerText({ timeout: 25000 });
    const rebooking = passenger.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/trips") &&
        response.request().method() === "POST",
    );
    await passenger
      .getByRole("button", { name: "Đặt chuyến ngay", exact: true })
      .click();
    const newBookingResponse = await rebooking;
    expect(newBookingResponse.status()).toBe(201);
    const newTrip = await newBookingResponse.json();
    expect(newTrip.id).not.toBe(originalTrip.id);
    tripId = newTrip.id;
    report.trip_id = tripId;
    expect(newTrip.quote_id).not.toBe(originalTrip.quote_id);
    expect(newTrip.status).toBe("SEARCHING");
    expect(newTrip.pickup).toEqual(originalTrip.pickup);
    expect(newTrip.dropoff).toEqual(originalTrip.dropoff);
    expect(newTrip.vehicle_type).toBe(originalTrip.vehicle_type);
    await expect(passenger).toHaveURL(new RegExp(`/trips/${tripId}$`));
    (report.checks as string[]).push(
      "driver cancels; rebook same pickup, dropoff and vehicle with a new quote and trip in SEARCHING",
    );
    await driver
      .getByRole("link", { name: "Không gian tài xế", exact: true })
      .click();
    await driver
      .getByRole("button", { name: "Nhận chuyến", exact: true })
      .click({ timeout: 20000 });
    await expect(
      driver.getByRole("heading", { name: "Tài xế đang đến", exact: true }),
    ).toBeVisible();
    await driver
      .getByRole("button", { name: "Tôi đã đến điểm đón", exact: true })
      .click();
    await expect(
      driver.getByRole("heading", { name: "Tài xế đã đến", exact: true }),
    ).toBeVisible();
    await driver
      .getByRole("button", { name: "Bắt đầu chuyến đi", exact: true })
      .click();
    await expect(
      driver.getByRole("heading", { name: "Đang di chuyển", exact: true }),
    ).toBeVisible();
    await driver
      .getByRole("button", { name: "Hoàn thành chuyến đi", exact: true })
      .click();
    await expect(
      driver.getByRole("heading", { name: "Đã hoàn thành", exact: true }),
    ).toBeVisible();
    await expect(
      passenger.getByRole("heading", { name: "Đã hoàn thành", exact: true }),
    ).toBeVisible();
    (report.checks as string[]).push(
      "arrive → start → complete; both UIs converge",
    );
    await expect(passenger.locator(".fare-line strong")).toHaveText(
      report.fare as string,
    );
    await expect(
      passenger.getByRole("heading", {
        name: "Đang chờ thanh toán",
        exact: true,
      }),
    ).toBeVisible();
    (report.checks as string[]).push(
      "immutable fare preserved; pending payment displayed honestly",
    );
    await passenger
      .getByRole("link", { name: "Chuyến đi", exact: true })
      .click();
    await expect(
      passenger.locator(`a[href="#/trips/${tripId}"]`),
    ).toBeVisible();
    await expect(
      passenger.locator(`a[href="#/trips/${originalTrip.id}"]`),
    ).toContainText("Đã hủy");
    await driver
      .getByRole("link", { name: "Không gian tài xế", exact: true })
      .click();
    await driver.getByRole("button", { name: "Ngừng nhận chuyến mới" }).click();
    await expect(
      driver.getByRole("button", { name: "Bật nhận chuyến", exact: true }),
    ).toBeEnabled();
    (report.checks as string[]).push("trip history + explicit driver offline");
    report.result = "PASS";
    await passenger.screenshot({
      path: testInfo.outputPath("live-history.png"),
      fullPage: true,
    });
  } finally {
    const browserRefreshes = await Promise.all(
      [driver, passenger, admin].map((page) =>
        page
          .evaluate(() => sessionStorage.getItem("ride.refresh.v1"))
          .catch(() => null),
      ),
    );
    // Stop GPS loops first. Fresh logins below use independent refresh-token families.
    await Promise.all(contexts.map((context) => context.close()));
    const cleanup = await browser.newContext({ baseURL });
    try {
      async function token(email: string) {
        const res = await cleanup.request.post(
          "/backend/user/api/v1/auth/login",
          { data: { email, password } },
        );
        return res.ok() ? await res.json() : null;
      }
      const rider = await token(accounts.passenger),
        partner = await token(accounts.driver);
      if (tripId && rider && partner) {
        const current = await cleanup.request.get(
          `/backend/trip/api/v1/trips/${tripId}`,
          { headers: { Authorization: `Bearer ${rider.access_token}` } },
        );
        if (current.ok()) {
          const trip = await current.json();
          if (!["COMPLETED", "CANCELLED", "NO_DRIVER"].includes(trip.status)) {
            const action =
                trip.status === "IN_PROGRESS" ? "complete" : "cancel",
              actor = action === "complete" ? partner : rider;
            const res = await cleanup.request.post(
              `/backend/trip/api/v1/trips/${tripId}/${action}`,
              {
                headers: {
                  Authorization: `Bearer ${actor.access_token}`,
                  "Idempotency-Key": randomUUID(),
                },
                data: {
                  expected_version: trip.version,
                  ...(action === "cancel"
                    ? { reason: "Cleanup FE live test" }
                    : {}),
                },
              },
            );
            if (res.ok()) {
              const op = await res.json();
              for (let i = 0; i < 20; i++) {
                const poll = await cleanup.request.get(
                  `/backend/trip/api/v1/operations/${op.id}`,
                  {
                    headers: { Authorization: `Bearer ${actor.access_token}` },
                  },
                );
                if (poll.ok() && (await poll.json()).status !== "PENDING")
                  break;
                await new Promise((resolve) => setTimeout(resolve, 500));
              }
            }
          }
        }
      }
      if (partner)
        await cleanup.request.patch(
          "/backend/driver/api/v1/drivers/me/availability",
          {
            headers: {
              Authorization: `Bearer ${partner.access_token}`,
              "Idempotency-Key": randomUUID(),
            },
            data: { accepting_trips: false },
          },
        );
      for (const pair of [rider, partner])
        if (pair)
          await cleanup.request.post("/backend/user/api/v1/auth/logout", {
            data: { refresh_token: pair.refresh_token },
          });
      report.cleanup =
        "Test driver offline; own admin demoted; test rows retained; temporary browsers closed.";
    } finally {
      if (adminId) adminRole("PASSENGER");
      for (const refreshToken of browserRefreshes)
        if (refreshToken)
          await cleanup.request
            .post("/backend/user/api/v1/auth/logout", {
              data: { refresh_token: refreshToken },
            })
            .catch(() => {});
      await cleanup.close();
      writeFileSync(
        path.resolve(process.cwd(), "../../deploy/frontend-flow-report.json"),
        JSON.stringify(report, null, 2),
      );
      writeFileSync(
        testInfo.outputPath("live-flow-report.json"),
        JSON.stringify(report, null, 2),
      );
      await testInfo.attach("live-flow-report", {
        body: JSON.stringify(report, null, 2),
        contentType: "application/json",
      });
    }
  }
});
