import { useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  Bike,
  Car,
  Clock3,
  MapPin,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { api, ApiError, mutationKey, newMutationKey } from "../lib/api";
import { navigate, useNow, useResource, useUser } from "../lib/hooks";
import type {
  MapsConfig,
  Place,
  Point,
  Quote,
  QuoteInput,
  Route,
  Trip,
  VehicleType,
} from "../lib/types";
import { money, terminal } from "../lib/format";
import { AddressInput } from "../components/AddressInput";
import { MapView } from "../components/MapView";
import { ErrorBox, Loading } from "../components/Common";

export function RebookingPage({ tripId }: { tripId: string }) {
  const user = useUser()!;
  const source = useResource(
    (signal) => api<Trip>("trip", `/api/v1/trips/${tripId}`, { signal }),
    [tripId],
  );
  if (source.loading) return <Loading text="Đang tải hành trình cũ…" />;
  if (source.error || !source.data)
    return (
      <div className="content-page">
        <ErrorBox error={source.error} retry={source.refresh} />
      </div>
    );
  if (source.data.passenger_id !== user.id || !terminal(source.data.status))
    return (
      <div className="content-page">
        <ErrorBox
          error={
            new Error(
              "Bạn có thể đặt lại hành trình sau khi chuyến cũ kết thúc.",
            )
          }
        />
      </div>
    );
  return <BookingPage initialTrip={source.data} />;
}

function savedPlace(point?: Point): Place | null {
  if (!point) return null;
  const coordinates = `${point.latitude}, ${point.longitude}`;
  return {
    ...point,
    place_id: coordinates,
    display_name: point.address || coordinates,
  };
}

export function BookingPage({ initialTrip }: { initialTrip?: Trip } = {}) {
  const user = useUser()!;
  const now = useNow();
  const quoteScope = initialTrip ? `quote-rebook-${initialTrip.id}` : "quote";
  const [pickup, setPickup] = useState(() => savedPlace(initialTrip?.pickup)),
    [dropoff, setDropoff] = useState(() => savedPlace(initialTrip?.dropoff)),
    [vehicle, setVehicle] = useState<VehicleType>(
      initialTrip?.vehicle_type || "MOTORBIKE",
    );
  const [route, setRoute] = useState<Route>(),
    [quote, setQuote] = useState<Quote>(),
    [busy, setBusy] = useState(false),
    [booking, setBooking] = useState(false),
    [error, setError] = useState<unknown>();
  const revision = useRef(0),
    activeRequest = useRef<AbortController | null>(null),
    creating = useRef(false);
  const trips = useResource(
    (signal) =>
      api<{ items: Trip[] }>("trip", "/api/v1/trips/me?limit=20", { signal }),
    [user.id],
    5000,
  );
  const config = useResource(
    (signal) =>
      api<MapsConfig>("pricing", "/maps/config", { auth: false, signal }),
    [],
  );
  const active = trips.data?.items.find((t) => !terminal(t.status));
  const seconds = quote
    ? Math.max(0, Math.ceil((Date.parse(quote.expires_at) - now) / 1000))
    : 0;
  useEffect(
    () => () => {
      activeRequest.current?.abort();
    },
    [],
  );
  function invalidate() {
    revision.current++;
    activeRequest.current?.abort();
    setBusy(false);
    setQuote(undefined);
    setRoute(undefined);
    setError(undefined);
  }
  const changePoint = (kind: "pickup" | "dropoff", value: Place | null) => {
    invalidate();
    if (kind === "pickup") setPickup(value);
    else setDropoff(value);
  };
  async function estimate() {
    if (!pickup || !dropoff || busy || creating.current) return;
    const version = revision.current,
      controller = new AbortController();
    activeRequest.current = controller;
    setBusy(true);
    setError(undefined);
    const point = (place: Place) => ({
      latitude: place.latitude,
      longitude: place.longitude,
      address: place.display_name.slice(0, 300),
    });
    const body: QuoteInput = {
      pickup: point(pickup),
      dropoff: point(dropoff),
      vehicle_type: vehicle,
    };
    if (quote && !seconds) newMutationKey(quoteScope);
    try {
      const result = await api<Route>("pricing", "/api/v1/maps/route", {
        method: "POST",
        body,
        signal: controller.signal,
      });
      const getPrice = () =>
        api<Quote>("pricing", "/api/v1/quotes", {
          method: "POST",
          body,
          key: mutationKey(quoteScope, body),
          signal: controller.signal,
        });
      let price = await getPrice();
      if (controller.signal.aborted) return;
      // Older tabs may still hold the idempotency key of a consumed quote.
      if (
        Date.parse(price.expires_at) <= Date.now() ||
        price.id === initialTrip?.quote_id ||
        trips.data?.items.some((trip) => trip.quote_id === price.id)
      ) {
        newMutationKey(quoteScope);
        price = await getPrice();
      }
      if (version === revision.current) {
        setRoute(result);
        setQuote(price);
      }
    } catch (e) {
      if (!controller.signal.aborted) setError(e);
    } finally {
      if (version === revision.current) setBusy(false);
    }
  }
  async function book() {
    if (!quote || !seconds || creating.current || active) return;
    creating.current = true;
    setBooking(true);
    setError(undefined);
    const body = { quote_id: quote.id, payment_method: "SIMULATED" };
    try {
      const trip = await api<Trip>("trip", "/api/v1/trips", {
        method: "POST",
        body,
        key: mutationKey("book", body),
      });
      newMutationKey(quoteScope);
      navigate(`/trips/${trip.id}`);
    } catch (e) {
      if (
        e instanceof ApiError &&
        ["QUOTE_ALREADY_USED", "INVALID_QUOTE"].includes(e.code)
      ) {
        newMutationKey(quoteScope);
        setQuote(undefined);
      }
      setError(e);
      trips.refresh();
    } finally {
      creating.current = false;
      setBooking(false);
    }
  }
  return (
    <div className="booking-layout">
      <section className="booking-panel">
        <div className="page-heading">
          <span className="eyebrow">MỘT CHUYẾN ĐI THẬT NHẸ</span>
          <h1>{initialTrip ? "Đặt lại hành trình" : "Bạn muốn đi đâu?"}</h1>
          <p className="muted">
            {initialTrip
              ? "Kiểm tra điểm đón, điểm đến và giá trước khi đặt chuyến mới."
              : "Chọn hành trình. Xem giá. Lên đường."}
          </p>
        </div>
        {active && (
          <a className="active-trip-banner" href={`#/trips/${active.id}`}>
            <span>
              <strong>Bạn đang có một chuyến đi</strong>
              <small>Theo dõi tài xế và trạng thái chuyến</small>
            </span>
            <ArrowRight size={20} />
          </a>
        )}
        <div className="card booking-card">
          <div className="route-inputs">
            <AddressInput
              label="Điểm đón"
              kind="pickup"
              value={pickup}
              onChange={(value) => changePoint("pickup", value)}
            />
            <AddressInput
              label="Điểm đến"
              kind="dropoff"
              value={dropoff}
              onChange={(value) => changePoint("dropoff", value)}
            />
          </div>
          <h3 className="small-heading">Chọn cách bạn di chuyển</h3>
          <div className="vehicle-options">
            {(["MOTORBIKE", "CAR"] as const).map((type) => (
              <button
                type="button"
                key={type}
                className={`vehicle-option ${vehicle === type ? "selected" : ""}`}
                aria-pressed={vehicle === type}
                onClick={() => {
                  if (type !== vehicle) {
                    invalidate();
                    setVehicle(type);
                  }
                }}
                disabled={booking}
              >
                {type === "CAR" ? <Car size={29} /> : <Bike size={29} />}
                <span>
                  <strong>{type === "CAR" ? "ride. Car" : "ride. Bike"}</strong>
                  <small>
                    {type === "CAR"
                      ? "Thoải mái cho mọi hành trình"
                      : "Gọn nhẹ, tiện lợi mỗi ngày"}
                  </small>
                </span>
                <i />
              </button>
            ))}
          </div>
          {vehicle === "MOTORBIKE" &&
            config.data?.motorbike_uses_driving_model && (
              <p className="subtle-note">
                Tuyến xe máy hiện dùng mô hình đường ô tô; chưa xét hạn chế
                riêng của xe máy.
              </p>
            )}
          <ErrorBox error={error} />
          <ErrorBox error={trips.error} retry={trips.refresh} />
          {!quote || !seconds ? (
            <button
              className="primary wide"
              disabled={!pickup || !dropoff || busy || booking || trips.loading}
              onClick={estimate}
            >
              {busy
                ? "Đang tìm tuyến và tính giá…"
                : quote
                  ? "Lấy giá mới"
                  : "Xem giá chuyến đi"}
              <ArrowRight size={18} />
            </button>
          ) : (
            <div className="quote-summary">
              <div className="quote-top">
                <span>Giá chuyến đi</span>
                <strong data-testid="quote-fare">{money(quote.fare)}</strong>
              </div>
              <div className="quote-metrics">
                <span>
                  <MapPin size={15} />
                  {(quote.estimated_distance_m / 1000).toLocaleString("vi-VN", {
                    maximumFractionDigits: 2,
                  })}{" "}
                  km
                </span>
                <span>
                  <Clock3 size={15} />
                  {Math.max(1, Math.ceil(quote.estimated_duration_s / 60))} phút
                </span>
              </div>
              <div className="expiry">
                <span>Giá được giữ trong {seconds}s</span>
                <progress value={seconds} max={120} />
              </div>
              <button
                className="primary wide"
                onClick={book}
                disabled={booking || !!active || trips.loading || !!trips.error}
              >
                {booking ? "Đang đặt chuyến…" : "Đặt chuyến ngay"}
                <ArrowRight size={18} />
              </button>
              <p className="subtle-note center">
                Thanh toán mô phỏng · Thời gian chưa tính giao thông trực tiếp
              </p>
            </div>
          )}
          {!!quote && !seconds && (
            <p className="field-hint" role="status">
              Giá đã hết hiệu lực. Hãy lấy giá mới trước khi đặt chuyến.
            </p>
          )}
        </div>
        <div className="booking-benefits">
          <span>
            <ShieldCheck size={18} /> Giá biết trước
          </span>
          <span>
            <Sparkles size={18} /> Hành trình rõ ràng
          </span>
        </div>
        <p className="privacy-note">
          Địa chỉ được gửi đến Photon và tọa độ tuyến đường được gửi đến OSRM.{" "}
          <a href="https://photon.komoot.io/" target="_blank" rel="noreferrer">
            Tìm hiểu
          </a>
        </p>
      </section>
      <MapView pickup={pickup} dropoff={dropoff} geometry={route?.geometry} />
    </div>
  );
}
