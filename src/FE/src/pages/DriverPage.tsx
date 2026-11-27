import { useState } from "react";
import type { FormEvent } from "react";
import {
  ArrowRight,
  Bike,
  Car,
  CheckCircle2,
  FileCheck2,
  MapPin,
  Power,
} from "lucide-react";
import { api, ApiError, mutationKey, newMutationKey } from "../lib/api";
import { navigate, useResource, useUser } from "../lib/hooks";
import { usePresence } from "../lib/presence";
import { useTripCommand } from "../lib/commands";
import { money, terminal } from "../lib/format";
import type { Driver, Trip, Vehicle } from "../lib/types";
import { Badge, Empty, ErrorBox, Loading } from "../components/Common";
import { MapView } from "../components/MapView";
async function optional<T>(load: Promise<T>) {
  try {
    return await load;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}
export function DriverPage() {
  const user = useUser()!,
    presence = usePresence();
  const profile = useResource(
    (signal) =>
      optional(api<Driver>("driver", "/api/v1/drivers/me", { signal })),
    [user.id],
    5000,
  );
  const vehicle = useResource(
    (signal) =>
      optional(
        api<Vehicle>("driver", "/api/v1/drivers/me/vehicle", { signal }),
      ),
    [user.id],
  );
  const trips = useResource(
    (signal) =>
      api<{ items: Trip[] }>("trip", "/api/v1/trips/me?limit=30", { signal }),
    [user.id],
    2000,
  );
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(),
    [editing, setEditing] = useState(false);
  const command = useTripCommand((action, id, success) => {
    trips.refresh();
    if (action === "accept" && success) navigate(`/trips/${id}`);
  });
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    const body = {
      license_number: String(
        new FormData(event.currentTarget).get("license_number"),
      ).trim(),
    };
    try {
      await api("driver", "/api/v1/drivers/me", {
        method: "POST",
        body,
        key: mutationKey("driver-profile", body),
      });
      profile.refresh();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  async function saveVehicle(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    const values = Object.fromEntries(new FormData(event.currentTarget));
    const body = { ...values, seat_count: Number(values.seat_count) };
    try {
      await api("driver", "/api/v1/drivers/me/vehicle", {
        method: "PUT",
        body,
        key: mutationKey("vehicle", body),
      });
      newMutationKey("vehicle");
      vehicle.refresh();
      setEditing(false);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  const active =
    trips.data?.items.filter((trip) => !terminal(trip.status)) || [];
  return (
    <div className="driver-layout">
      <section className="driver-panel">
        <div className="page-heading">
          <span className="eyebrow">MỖI CHUYẾN XE, THÊM MỘT KẾT NỐI</span>
          <h1>
            Chào {user.display_name.split(" ").at(-1)}{" "}
            <span className="wave">↗</span>
          </h1>
          <p className="muted">Sẵn sàng cho hành trình tiếp theo?</p>
        </div>
        <ErrorBox error={profile.error} retry={profile.refresh} />
        <ErrorBox error={vehicle.error} retry={vehicle.refresh} />
        <ErrorBox error={error} />
        {profile.loading ? (
          <Loading />
        ) : profile.data === null ? (
          <form className="card" onSubmit={create}>
            <div className="section-icon">
              <FileCheck2 />
            </div>
            <h2>Trở thành đối tác ride.</h2>
            <p className="muted">
              Tạo hồ sơ tài xế và thêm phương tiện. Hồ sơ cần được duyệt trước
              khi nhận chuyến.
            </p>
            <label>
              Số giấy phép lái xe
              <input
                name="license_number"
                required
                maxLength={50}
                placeholder="Nhập số giấy phép của bạn"
              />
            </label>
            <button className="primary wide" disabled={busy}>
              Gửi hồ sơ tài xế
            </button>
          </form>
        ) : (
          profile.data && (
            <>
              <div className="card availability-card">
                <div
                  className={`online-indicator ${presence.online ? "online" : ""}`}
                >
                  <Power size={24} />
                </div>
                <div>
                  <h2>
                    {presence.online
                      ? "Bạn đang trực tuyến"
                      : "Bắt đầu nhận chuyến"}
                  </h2>
                  <p className="muted">
                    {presence.online
                      ? "Giữ trang mở và cho phép định vị để nhận chuyến gần bạn."
                      : "Bật định vị để kết nối với hành khách gần bạn."}
                  </p>
                </div>
                <button
                  className={
                    presence.online ? "secondary wide" : "primary wide"
                  }
                  disabled={
                    presence.busy ||
                    profile.data.account_status !== "APPROVED" ||
                    !vehicle.data
                  }
                  onClick={presence.online ? presence.stop : presence.start}
                >
                  {presence.busy
                    ? "Đang cập nhật…"
                    : presence.online
                      ? "Ngừng nhận chuyến mới"
                      : "Bật nhận chuyến"}
                </button>
                <ErrorBox error={presence.error} />
                {profile.data.account_status !== "APPROVED" && (
                  <div className="notice">
                    <FileCheck2 size={18} />
                    {profile.data.account_status === "PENDING"
                      ? "Hồ sơ đang chờ quản trị viên duyệt. Bạn có thể thêm thông tin xe bên dưới."
                      : "Hồ sơ hiện chưa được phép nhận chuyến. Vui lòng liên hệ quản trị viên."}
                  </div>
                )}
                {profile.data.accepting_trips && !presence.online && (
                  <p className="subtle-note">
                    Bật nhận chuyến để bắt đầu lại phiên định vị trên thiết bị
                    này.
                  </p>
                )}
              </div>
              <div className="card">
                <div className="heading-row">
                  <h3>Phương tiện của bạn</h3>
                  {vehicle.data && (
                    <button
                      className="text-button"
                      onClick={() => setEditing((v) => !v)}
                      disabled={presence.online || active.length > 0}
                    >
                      Chỉnh sửa
                    </button>
                  )}
                </div>
                {vehicle.loading && <Loading />}
                {vehicle.data && !editing ? (
                  <div className="vehicle-summary">
                    {vehicle.data.vehicle_type === "CAR" ? (
                      <Car size={34} />
                    ) : (
                      <Bike size={34} />
                    )}
                    <div>
                      <strong>
                        {vehicle.data.brand} {vehicle.data.model}
                      </strong>
                      <p>
                        {vehicle.data.plate_number} · {vehicle.data.color}
                      </p>
                    </div>
                    <CheckCircle2 className="green" size={19} />
                  </div>
                ) : (
                  <form onSubmit={saveVehicle} key={vehicle.data?.id || "new"}>
                    <div className="form-grid">
                      <label>
                        Loại xe
                        <select
                          name="vehicle_type"
                          defaultValue={
                            vehicle.data?.vehicle_type || "MOTORBIKE"
                          }
                        >
                          <option value="MOTORBIKE">Xe máy</option>
                          <option value="CAR">Ô tô</option>
                        </select>
                      </label>
                      <label>
                        Biển số
                        <input
                          name="plate_number"
                          defaultValue={vehicle.data?.plate_number}
                          required
                          maxLength={20}
                          placeholder="29-A1 123.45"
                        />
                      </label>
                      <label>
                        Hãng xe
                        <input
                          name="brand"
                          defaultValue={vehicle.data?.brand}
                          required
                          maxLength={80}
                          placeholder="Honda"
                        />
                      </label>
                      <label>
                        Dòng xe
                        <input
                          name="model"
                          defaultValue={vehicle.data?.model}
                          required
                          maxLength={80}
                          placeholder="Vision"
                        />
                      </label>
                      <label>
                        Màu sắc
                        <input
                          name="color"
                          defaultValue={vehicle.data?.color}
                          required
                          maxLength={40}
                          placeholder="Trắng"
                        />
                      </label>
                      <label>
                        Số chỗ
                        <input
                          name="seat_count"
                          type="number"
                          min={1}
                          max={16}
                          defaultValue={vehicle.data?.seat_count || 2}
                          required
                        />
                      </label>
                    </div>
                    <button
                      className="primary wide"
                      disabled={busy || presence.online || active.length > 0}
                    >
                      Lưu phương tiện
                    </button>
                  </form>
                )}
              </div>
              <div className="heading-row">
                <h2>Chuyến đi hiện tại</h2>
                <span className="live-tag">
                  <i /> Cập nhật tự động
                </span>
              </div>
              <ErrorBox error={trips.error} retry={trips.refresh} />
              <ErrorBox
                error={command.error}
                retry={command.pending ? command.retry : undefined}
              />
              {command.pending && (
                <div className="notice">
                  Đang xử lý thao tác chuyến đi.{" "}
                  <a href={`#/trips/${command.pending.tripId}`}>
                    Xem tiến trình
                  </a>
                </div>
              )}
              {!active.length && !trips.loading && (
                <Empty
                  title={
                    presence.online
                      ? "Đang chờ hành trình mới"
                      : "Chưa có chuyến đang diễn ra"
                  }
                  text={
                    presence.online
                      ? "Chúng tôi sẽ hiển thị chuyến phù hợp ngay tại đây."
                      : "Bật nhận chuyến khi bạn sẵn sàng lên đường."
                  }
                />
              )}
              {active.map((trip) => (
                <div className="card offer-card" key={trip.id}>
                  <div className="heading-row">
                    <Badge status={trip.status} />
                    <strong>{money(trip.estimated_fare)}</strong>
                  </div>
                  <div className="offer-stops">
                    <MapPin size={18} />
                    <div>
                      <strong>{trip.pickup.address || "Điểm đón"}</strong>
                      <p>Đến: {trip.dropoff.address || "Điểm đến"}</p>
                    </div>
                  </div>
                  {trip.status === "DRIVER_OFFERED" ? (
                    <div className="button-row">
                      <button
                        className="secondary"
                        disabled={!!command.pending}
                        onClick={() => command.submit(trip, "reject")}
                      >
                        Từ chối
                      </button>
                      <button
                        className="primary"
                        disabled={!!command.pending}
                        onClick={() => command.submit(trip, "accept")}
                      >
                        Nhận chuyến <ArrowRight size={16} />
                      </button>
                    </div>
                  ) : (
                    <a href={`#/trips/${trip.id}`} className="primary wide">
                      Tiếp tục chuyến đi <ArrowRight size={17} />
                    </a>
                  )}
                </div>
              ))}
            </>
          )
        )}
      </section>
      <MapView
        driver={presence.point}
        pickup={active[0]?.pickup}
        dropoff={active[0]?.dropoff}
      />
    </div>
  );
}
