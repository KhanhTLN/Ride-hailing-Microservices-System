import { test, expect } from "@playwright/test";
import { addresses, fixture, ids, login } from "./fixtures";
test("passenger: autocomplete → quote → create once → recover uncertain cancel after reload", async ({
  page,
}) => {
  const { state } = await fixture(page, "PASSENGER", {
    lostCancel: true,
    expiredQuote: true,
  });
  await login(page);
  await addresses(page);
  await page
    .getByRole("button", { name: "Xem giá chuyến đi", exact: true })
    .click();
  await expect(page.getByTestId("quote-fare")).toContainText("32.000");
  expect(state.quotes).toBe(2);
  expect(new Set(state.quoteKeys).size).toBe(2);
  await page
    .getByRole("button", { name: "Đặt chuyến ngay", exact: true })
    .dblclick();
  await expect(page).toHaveURL(new RegExp(`/trips/${ids.trip}`));
  expect(state.creates).toBe(1);
  await page.getByRole("button", { name: "Hủy chuyến", exact: true }).click();
  await page.getByLabel("Lý do hủy chuyến").fill("Tôi cần đổi lịch trình");
  await page.getByRole("button", { name: "Xác nhận hủy", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Đã hủy", exact: true }),
  ).toBeVisible();
  expect(state.cancels).toHaveLength(2);
  expect(state.cancels[0]).toEqual(state.cancels[1]);
  await page.getByRole("link", { name: "Chuyến đi", exact: true }).click();
  await expect(
    page.getByText("Lăng Bác, Ba Đình, Hà Nội", { exact: true }),
  ).toBeVisible();
  expect(state.failures).toEqual([]);
});
test("driver: GPS online → offer → accept → arrive → start → complete → offline", async ({
  page,
  context,
}) => {
  await context.grantPermissions(["geolocation"]);
  await context.setGeolocation({ latitude: 21.0242, longitude: 105.8578 });
  const { state, profile } = await fixture(page, "DRIVER");
  await login(page);
  await page
    .getByRole("button", { name: "Bật nhận chuyến", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Bạn đang trực tuyến" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Nhận chuyến", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Tài xế đang đến", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Tôi đã đến điểm đón", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Tài xế đã đến", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Bắt đầu chuyến đi", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Đang di chuyển", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Hoàn thành chuyến đi", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Đã hoàn thành", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Đang chờ thanh toán" }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Không gian tài xế", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Ngừng nhận chuyến mới", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Bật nhận chuyến", exact: true }),
  ).toBeEnabled();
  expect(profile.accepting_trips).toBe(false);
  expect(state.locations).toBeGreaterThan(0);
  expect(state.failures).toEqual([]);
});
test("register a driver and submit profile + vehicle while approval is pending", async ({
  page,
}) => {
  const { state } = await fixture(page, "DRIVER", { noProfile: true });
  await page.goto("/");
  await page
    .getByRole("button", { name: "Tạo tài khoản", exact: true })
    .click();
  await page.getByRole("button", { name: "Tôi là tài xế" }).click();
  await page.getByLabel("Tên hiển thị").fill("Minh Tài Xế");
  await page.getByLabel("Email", { exact: true }).fill("driver@example.test");
  await page
    .getByLabel("Mật khẩu", { exact: true })
    .fill("Correct-Password-12345");
  await page
    .getByRole("button", { name: "Tạo tài khoản", exact: true })
    .click();
  await page.getByLabel("Số giấy phép lái xe").fill("GPLX-TEST");
  await page.getByRole("button", { name: "Gửi hồ sơ tài xế" }).click();
  await expect(
    page.getByRole("button", { name: "Bật nhận chuyến", exact: true }),
  ).toBeDisabled();
  await page.getByLabel("Biển số").fill("29TEST");
  await page.getByLabel("Hãng xe").fill("Honda");
  await page.getByLabel("Dòng xe").fill("Vision");
  await page.getByLabel("Màu sắc").fill("Trắng");
  await page
    .getByRole("button", { name: "Lưu phương tiện", exact: true })
    .click();
  await expect(page.getByText("Honda Vision", { exact: true })).toBeVisible();
  expect(state.registrations[0].role).toBe("DRIVER");
  expect(state.failures).toEqual([]);
});
test("admin: approve profile with reason; passenger routes are protected", async ({
  page,
}) => {
  const { profile, state } = await fixture(page, "ADMIN");
  await login(page);
  await page.getByRole("button", { name: "Xét duyệt", exact: true }).click();
  await page.getByLabel("Lý do").fill("Đã kiểm tra hồ sơ hợp lệ");
  await page.getByRole("button", { name: "Xác nhận", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(profile.account_status).toBe("APPROVED");
  await page.goto("/#/book");
  await expect(
    page.getByRole("heading", { name: "Trang này chưa có ở đây" }),
  ).toBeVisible();
  expect(state.failures).toEqual([]);
});
test("wrong password is shown by the form; mobile layout and account update", async ({
  page,
}) => {
  const { state } = await fixture(page);
  await page.goto("/");
  await page.getByLabel("Email", { exact: true }).fill("rider@example.test");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("wrong");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Email hoặc mật khẩu");
  await login(page);
  await page.getByRole("link", { name: "Tài khoản", exact: true }).click();
  await page.getByLabel("Email", { exact: true }).fill("updated@example.test");
  await page.getByRole("button", { name: "Lưu thay đổi" }).click();
  await expect(page.getByRole("status")).toContainText("Đã cập nhật email");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Mở menu" }).click();
  await page.getByRole("link", { name: "Đặt chuyến", exact: true }).click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/mobile-booking.png",
    fullPage: true,
  });
  expect(state.failures).toEqual([]);
});
