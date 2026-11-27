import { createContext, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { api, ApiError, mutationKey, newMutationKey } from "./api";
import { useUser } from "./hooks";
import type { Point } from "./types";
function gps(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error("Trình duyệt không hỗ trợ định vị."));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      resolve,
      () =>
        reject(
          new Error("Chưa lấy được vị trí. Hãy cho phép định vị và thử lại."),
        ),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 5000 },
    );
  });
}
const Context = createContext<{
  online: boolean;
  busy: boolean;
  point: Point | null;
  error: unknown;
  start: () => Promise<void>;
  stop: () => Promise<void>;
} | null>(null);
export const usePresence = () => useContext(Context)!;
export function PresenceProvider({ children }: { children: ReactNode }) {
  const user = useUser()!;
  const [online, setOnline] = useState(false),
    [busy, setBusy] = useState(false),
    [point, setPoint] = useState<Point | null>(null),
    [error, setError] = useState<unknown>();
  const current = useRef<{ id: string; sequence: number } | null>(null),
    lifecycle = useRef(0),
    working = useRef(false);
  async function send(position: GeolocationPosition, signal?: AbortSignal) {
    const active = current.current;
    if (!active) return;
    const p = {
      latitude: position.coords.latitude,
      longitude: position.coords.longitude,
    };
    const body = {
      session_id: active.id,
      sequence_number: active.sequence++,
      point: p,
      recorded_at: new Date(position.timestamp).toISOString(),
    };
    await api("driver", "/api/v1/drivers/me/location", {
      method: "PUT",
      body,
      signal,
    });
    await api("driver", "/api/v1/drivers/me/heartbeat", {
      method: "POST",
      body: { session_id: active.id },
      signal,
    });
    setPoint(p);
  }
  useEffect(() => {
    if (!online) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const generation = lifecycle.current;
    async function tick() {
      try {
        const position = await gps();
        if (controller.signal.aborted || generation !== lifecycle.current)
          return;
        await send(position, controller.signal);
        if (!controller.signal.aborted) setError(undefined);
      } catch (e) {
        if (!controller.signal.aborted) setError(e);
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(tick, 12000);
      }
    }
    timer = setTimeout(tick, 12000);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [online, user.id]);
  useEffect(
    () => () => {
      lifecycle.current++;
      current.current = null;
    },
    [],
  );
  async function start() {
    if (working.current || online) return;
    working.current = true;
    setBusy(true);
    setError(undefined);
    const generation = lifecycle.current;
    try {
      const position = await gps();
      if (generation !== lifecycle.current) return;
      const session = await api<{ session_id: string }>(
        "driver",
        "/api/v1/drivers/me/location-sessions",
        { method: "POST", key: mutationKey("location-session", {}) },
      );
      if (generation !== lifecycle.current) return;
      current.current = { id: session.session_id, sequence: 0 };
      // A previous attempt may have written a location before its response was lost.
      try {
        const previous = await api<{
          session_id: string;
          sequence_number: number;
        }>("driver", "/api/v1/drivers/me/location");
        if (previous.session_id === session.session_id)
          current.current.sequence = previous.sequence_number + 1;
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 404)) throw e;
      }
      if (generation !== lifecycle.current) return;
      const body = { accepting_trips: true };
      await api("driver", "/api/v1/drivers/me/availability", {
        method: "PATCH",
        body,
        key: mutationKey("availability", body),
      });
      if (generation !== lifecycle.current) return;
      await send(position);
      if (generation !== lifecycle.current) return;
      newMutationKey("location-session");
      newMutationKey("availability");
      setOnline(true);
    } catch (e) {
      setError(e);
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  async function stop() {
    if (working.current) return;
    working.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const body = { accepting_trips: false };
      await api("driver", "/api/v1/drivers/me/availability", {
        method: "PATCH",
        body,
        key: mutationKey("availability", body),
      });
      lifecycle.current++;
      setOnline(false);
      newMutationKey("availability");
    } catch (e) {
      setError(e);
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  return (
    <Context.Provider value={{ online, busy, point, error, start, stop }}>
      {children}
    </Context.Provider>
  );
}
