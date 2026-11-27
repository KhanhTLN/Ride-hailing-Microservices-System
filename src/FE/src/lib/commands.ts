import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "./api";
import type { Operation, Trip, TripAction } from "./types";
import { useUser } from "./hooks";
interface Command {
  tripId: string;
  action: TripAction;
  body: {
    expected_version: number;
    attempt_id?: string;
    reservation_id?: string;
    reason?: string;
  };
  key: string;
  operation?: Operation;
}
export function useTripCommand(
  onDone: (action: TripAction, tripId: string, success: boolean) => void,
) {
  const user = useUser()!,
    storage = `ride.command.${user.id}`;
  const [command, setCommand] = useState<Command | null>(() => {
    try {
      return JSON.parse(sessionStorage.getItem(storage) || "null");
    } catch {
      return null;
    }
  });
  const [error, setError] = useState<unknown>(),
    [revision, setRevision] = useState(0);
  const done = useRef(onDone);
  done.current = onDone;
  const commandRef = useRef(command);
  commandRef.current = command;
  const save = (value: Command | null) => {
    if (value) sessionStorage.setItem(storage, JSON.stringify(value));
    else sessionStorage.removeItem(storage);
    commandRef.current = value;
    setCommand(value);
  };
  useEffect(() => {
    if (!command) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const current = { ...command };
    setError(undefined);
    async function poll() {
      try {
        if (!current.operation) {
          current.operation = await api<Operation>(
            "trip",
            `/api/v1/trips/${current.tripId}/${current.action}`,
            {
              method: "POST",
              body: current.body,
              key: current.key,
              signal: controller.signal,
            },
          );
          sessionStorage.setItem(storage, JSON.stringify(current));
          commandRef.current = current;
        } else
          current.operation = await api<Operation>(
            "trip",
            `/api/v1/operations/${current.operation.id}`,
            { signal: controller.signal },
          );
        if (controller.signal.aborted) return;
        if (current.operation.status === "PENDING")
          timer = setTimeout(poll, 1200);
        else {
          save(null);
          if (current.operation.status === "FAILED")
            setError(
              new ApiError(
                409,
                current.operation.error_code || "OPERATION_FAILED",
                "Thao tác chưa hoàn tất. Vui lòng kiểm tra lại trạng thái chuyến.",
              ),
            );
          done.current(
            current.action,
            current.tripId,
            current.operation.status === "SUCCEEDED",
          );
        }
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e);
        // A transport/server failure may have accepted the command. Retain its original key/body.
        if (
          e instanceof ApiError &&
          e.status >= 400 &&
          e.status < 500 &&
          ![408, 429].includes(e.status)
        ) {
          save(null);
          done.current(current.action, current.tripId, false);
        }
      }
    }
    void poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [command, revision, storage]);
  function submit(trip: Trip, action: TripAction, reason?: string) {
    if (commandRef.current) return;
    const body: Command["body"] = { expected_version: trip.version };
    if (action === "accept" || action === "reject") {
      body.attempt_id = trip.attempt_id;
      body.reservation_id = trip.reservation_id;
    }
    if (action === "cancel") body.reason = reason;
    save({ tripId: trip.id, action, body, key: crypto.randomUUID() });
  }
  return {
    pending: command,
    error,
    submit,
    retry: () => setRevision((v) => v + 1),
  };
}
