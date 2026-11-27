import { useState } from "react";
import { ArrowLeft, Check, Clock3, MapPin, ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import { navigate, useResource, useUser } from "../lib/hooks";
import { useTripCommand } from "../lib/commands";
import { money, terminal, tripLabels, when } from "../lib/format";
import type { Route, Trip, TripAction } from "../lib/types";
import { Badge, ErrorBox, Loading } from "../components/Common";
import { MapView } from "../components/MapView";
const details: Record<string, string> = {
  SEARCHING: "Đang kết nối với tài xế gần điểm đón của bạn.",
  DRIVER_OFFERED: "Một tài xế phù hợp đã nhận được lời mời chuyến đi.",
  ACCEPTED: "Tài xế đã nhận chuyến và đang di chuyển đến điểm đón.",
  ARRIVED: "Tài xế đã tới điểm đón. Hãy chuẩn bị lên xe.",
  IN_PROGRESS: "Chúc bạn có một chuyến đi an toàn và dễ chịu.",
  COMPLETED: "Cảm ơn bạn đã đồng hành cùng ride.",
  CANCELLED: "Chuyến đi đã được hủy. Bạn có thể đặt lại cùng hành trình.",
  NO_DRIVER: "Chưa kết nối được tài xế phù hợp. Bạn có thể thử đặt lại.",
};
export function TripPage({ id }: { id: string }) {
  const user = useUser()!,
    isDriver = user.roles.includes("DRIVER");
  const resource = useResource(
    (signal) => api<Trip>("trip", `/api/v1/trips/${id}`, { signal }),
    [id, user.id],
    2500,
  );
  const [cancelling, setCancelling] = useState(false),
    [reason, setReason] = useState("");
  const command = useTripCommand((action, _id, success) => {
    setCancelling(false);
    if (action === "reject" && success) navigate("/driver");
    else resource.refresh();
  });
  const trip = resource.data;
  if (resource.loading) return <Loading text="Đang tải hành trình…" />;
  if (!trip)
    return (
      <div className="content-page">
        <a href="#/trips" className="back-link">
          <ArrowLeft size={16} /> Chuyến đi của bạn
        </a>
        <ErrorBox error={resource.error} retry={resource.refresh} />
      </div>
    );
  const driverAction: Record<string, [TripAction, string]> = {
    ACCEPTED: ["arrive", "Tôi đã đến điểm đón"],
    ARRIVED: ["start", "Bắt đầu chuyến đi"],
    IN_PROGRESS: ["complete", "Hoàn thành chuyến đi"],
  };
  const canCancel = isDriver
    ? ["ACCEPTED", "ARRIVED"].includes(trip.status)
    : !terminal(trip.status) && trip.status !== "IN_PROGRESS";
  const progress = [
    "SEARCHING",
    "ACCEPTED",
    "ARRIVED",
    "IN_PROGRESS",
    "COMPLETED",
  ];
  const step =
    trip.status === "DRIVER_OFFERED" ? 0 : progress.indexOf(trip.status);
  return (
    <div className="booking-layout trip-detail">
      <section className="booking-panel">
        <a className="back-link" href={isDriver ? "#/driver" : "#/trips"}>
          <ArrowLeft size={16} /> Quay lại
        </a>
        <div className="page-heading">
          <Badge status={trip.status} />
          <h1>{tripLabels[trip.status]}</h1>
          <p className="muted">{details[trip.status]}</p>
        </div>
        <ErrorBox error={resource.error} />
        <div className="card">
          <div className="trip-stops">
            <div>
              <span className="point-dot pickup" />
              <span>
                <small>Điểm đón</small>
                <strong>
                  {trip.pickup.address ||
                    `${trip.pickup.latitude}, ${trip.pickup.longitude}`}
                </strong>
              </span>
            </div>
            <div>
              <span className="point-dot dropoff" />
              <span>
                <small>Điểm đến</small>
                <strong>
                  {trip.dropoff.address ||
                    `${trip.dropoff.latitude}, ${trip.dropoff.longitude}`}
                </strong>
              </span>
            </div>
          </div>
          <div className="fare-line">
            <span>
              {trip.status === "COMPLETED"
                ? "Giá chuyến đi"
                : "Giá đã xác nhận"}
            </span>
            <strong>{money(trip.final_fare || trip.estimated_fare)}</strong>
          </div>
          <p className="subtle-note">
            {trip.vehicle_type === "CAR" ? "ride. Car" : "ride. Bike"} ·{" "}
            {when(trip.created_at)}
          </p>
        </div>
        {!["CANCELLED", "NO_DRIVER"].includes(trip.status) && (
          <div className="card timeline">
            {[
              "Tìm tài xế",
              "Đã nhận chuyến",
              "Tới điểm đón",
              "Đang di chuyển",
              "Hoàn thành",
            ].map((title, index) => (
              <div key={title} className={index <= step ? "done" : ""}>
                <i>{index < step ? <Check size={13} /> : index + 1}</i>
                <span>{title}</span>
                {index === step && !terminal(trip.status) && (
                  <span className="pulse-dot" />
                )}
              </div>
            ))}
          </div>
        )}
        {trip.status === "COMPLETED" && (
          <div className="card payment-card">
            <ShieldCheck size={25} />
            <div>
              <h3>
                {trip.payment_status === "SUCCEEDED"
                  ? "Thanh toán thành công"
                  : trip.payment_status === "FAILED"
                    ? "Thanh toán chưa thành công"
                    : "Đang chờ thanh toán"}
              </h3>
              <p className="muted">
                {trip.payment_status === "SUCCEEDED"
                  ? "Chuyến đi đã được ghi nhận thanh toán."
                  : trip.payment_status === "FAILED"
                    ? "Bạn có thể yêu cầu xử lý lại thanh toán."
                    : "Chúng tôi sẽ cập nhật khi nhận được kết quả. Chuyến đi của bạn đã hoàn thành."}
              </p>
            </div>
          </div>
        )}
        {command.pending && (
          <div className="operation-notice" role="status">
            <Clock3 size={20} />
            <span>
              Thao tác đang được xử lý. Bạn có thể quay lại trang này để theo
              dõi kết quả.
            </span>
          </div>
        )}
        <ErrorBox
          error={command.error}
          retry={command.pending ? command.retry : undefined}
        />
        <div className="trip-actions">
          {isDriver && trip.status === "DRIVER_OFFERED" && (
            <>
              <button
                className="primary wide"
                disabled={!!command.pending}
                onClick={() => command.submit(trip, "accept")}
              >
                Nhận chuyến
              </button>
              <button
                className="secondary wide"
                disabled={!!command.pending}
                onClick={() => command.submit(trip, "reject")}
              >
                Từ chối chuyến
              </button>
            </>
          )}
          {isDriver && driverAction[trip.status] && (
            <button
              className="primary wide"
              disabled={!!command.pending}
              onClick={() => command.submit(trip, driverAction[trip.status][0])}
            >
              {driverAction[trip.status][1]}
            </button>
          )}
          {!isDriver &&
            trip.status === "COMPLETED" &&
            trip.payment_status === "FAILED" && (
              <button
                className="primary wide"
                disabled={!!command.pending}
                onClick={() => command.submit(trip, "retry-payment")}
              >
                Thử thanh toán lại
              </button>
            )}
          {canCancel && !cancelling && (
            <button
              className="danger-outline wide"
              disabled={!!command.pending}
              onClick={() => setCancelling(true)}
            >
              Hủy chuyến
            </button>
          )}
          {cancelling && (
            <form
              className="card cancel-form"
              onSubmit={(event) => {
                event.preventDefault();
                command.submit(trip, "cancel", reason.trim());
              }}
            >
              <label>
                Lý do hủy chuyến
                <textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  maxLength={300}
                  required
                  placeholder="Cho chúng tôi biết lý do của bạn"
                />
              </label>
              <div className="button-row">
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setCancelling(false)}
                >
                  Giữ chuyến
                </button>
                <button
                  className="danger"
                  disabled={!!command.pending || !reason.trim()}
                >
                  Xác nhận hủy
                </button>
              </div>
            </form>
          )}
          {terminal(trip.status) && !isDriver && (
            <a href={`#/book/rebook/${trip.id}`} className="primary wide">
              Đặt lại hành trình <MapPin size={17} />
            </a>
          )}
        </div>
        <p className="privacy-note">Mã chuyến: {trip.id}</p>
      </section>
      <TripMap trip={trip} />
    </div>
  );
}
function TripMap({ trip }: { trip: Trip }) {
  const route = useResource(
    (signal) =>
      api<Route>("pricing", "/api/v1/maps/route", {
        method: "POST",
        body: {
          pickup: trip.pickup,
          dropoff: trip.dropoff,
          vehicle_type: trip.vehicle_type,
        },
        signal,
      }),
    [trip.id],
  );
  return (
    <div className="trip-map-wrap">
      <MapView
        pickup={trip.pickup}
        dropoff={trip.dropoff}
        geometry={route.data?.geometry}
      />
      {!!route.error && (
        <div className="route-map-error">
          <ErrorBox error={route.error} retry={route.refresh} />
        </div>
      )}
    </div>
  );
}
