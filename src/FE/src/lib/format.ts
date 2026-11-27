import type { Money, TripStatus } from "./types";
export const money = (value?: Money) =>
  value
    ? new Intl.NumberFormat("vi-VN", {
        style: "currency",
        currency: value.currency,
      }).format(Number(value.amount))
    : "—";
export const when = (value: string) =>
  new Date(value).toLocaleString("vi-VN", {
    dateStyle: "short",
    timeStyle: "short",
  });
export const tripLabels: Record<TripStatus, string> = {
  SEARCHING: "Đang tìm tài xế",
  DRIVER_OFFERED: "Đang chờ tài xế nhận",
  ACCEPTED: "Tài xế đang đến",
  ARRIVED: "Tài xế đã đến",
  IN_PROGRESS: "Đang di chuyển",
  COMPLETED: "Đã hoàn thành",
  CANCELLED: "Đã hủy",
  NO_DRIVER: "Chưa tìm được tài xế",
};
export const terminal = (status: TripStatus) =>
  ["COMPLETED", "CANCELLED", "NO_DRIVER"].includes(status);
export const roleLabel = (role?: string) =>
  role === "DRIVER"
    ? "Tài xế"
    : role === "ADMIN"
      ? "Quản trị viên"
      : "Hành khách";
