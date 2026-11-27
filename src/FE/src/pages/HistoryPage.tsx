import { useState } from "react";
import { ArrowUpRight, Bike, Car, RefreshCw } from "lucide-react";
import { api } from "../lib/api";
import { useResource, useUser } from "../lib/hooks";
import { money, terminal, when } from "../lib/format";
import type { Trip } from "../lib/types";
import { Badge, Empty, ErrorBox, Loading } from "../components/Common";
export function HistoryPage() {
  const user = useUser()!;
  const [filter, setFilter] = useState("all");
  const resource = useResource(
    (signal) =>
      api<{ items: Trip[] }>("trip", "/api/v1/trips/me?limit=100", { signal }),
    [user.id],
    5000,
  );
  const items = resource.data?.items.filter(
    (t) =>
      filter === "all" ||
      (filter === "active"
        ? !terminal(t.status)
        : filter === "completed"
          ? t.status === "COMPLETED"
          : ["CANCELLED", "NO_DRIVER"].includes(t.status)),
  );
  return (
    <div className="content-page">
      <div className="page-heading heading-row">
        <div>
          <span className="eyebrow">MỖI CHUYẾN ĐI LÀ MỘT KỶ NIỆM</span>
          <h1>Chuyến đi của bạn</h1>
          <p className="muted">
            100 chuyến gần nhất, mọi hành trình ở cùng một nơi.
          </p>
        </div>
        <button className="secondary" onClick={resource.refresh}>
          <RefreshCw size={16} /> Làm mới
        </button>
      </div>
      <div className="filter-tabs">
        {[
          ["all", "Tất cả"],
          ["active", "Đang diễn ra"],
          ["completed", "Hoàn thành"],
          ["cancelled", "Đã kết thúc khác"],
        ].map(([key, title]) => (
          <button
            className={filter === key ? "selected" : ""}
            key={key}
            onClick={() => setFilter(key)}
          >
            {title}
          </button>
        ))}
      </div>
      <ErrorBox error={resource.error} retry={resource.refresh} />
      {resource.loading && <Loading />}
      {items && !items.length && (
        <Empty
          title="Chưa có chuyến đi nào ở đây"
          text="Những hành trình của bạn sẽ được lưu lại tại đây."
        />
      )}
      <div className="trip-list">
        {items?.map((trip) => (
          <a
            className="trip-row card"
            key={trip.id}
            href={`#/trips/${trip.id}`}
          >
            <div className="trip-vehicle">
              {trip.vehicle_type === "CAR" ? <Car /> : <Bike />}
            </div>
            <div className="trip-row-main">
              <div>
                <Badge status={trip.status} />
                <time>{when(trip.created_at)}</time>
              </div>
              <h3>{trip.dropoff.address || "Điểm đến đã chọn"}</h3>
              <p>{trip.pickup.address || "Điểm đón đã chọn"}</p>
            </div>
            <div className="trip-row-price">
              <strong>{money(trip.final_fare || trip.estimated_fare)}</strong>
              <ArrowUpRight size={20} />
            </div>
          </a>
        ))}
      </div>
    </div>
  );
}
