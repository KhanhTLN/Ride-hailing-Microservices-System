import { AlertCircle, ArrowRight, LoaderCircle } from "lucide-react";
import { ApiError, errorText } from "../lib/api";
import type { TripStatus } from "../lib/types";
import { tripLabels } from "../lib/format";
export function Brand() {
  return (
    <a className="brand" href="#/" aria-label="ride — Trang chủ">
      ride<span>.</span>
    </a>
  );
}
export function Loading({ text = "Đang tải…" }: { text?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle className="spin" size={19} />
      {text}
    </div>
  );
}
export function ErrorBox({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  if (!error) return null;
  return (
    <div className="error-box" role="alert">
      <AlertCircle size={19} />
      <div>
        {errorText(error)}
        {error instanceof ApiError && error.correlation && (
          <small>Mã hỗ trợ: {error.correlation}</small>
        )}
        {retry && (
          <button className="text-button" onClick={retry}>
            Thử lại <ArrowRight size={14} />
          </button>
        )}
      </div>
    </div>
  );
}
export function Badge({ status }: { status: TripStatus }) {
  return (
    <span className={`badge status-${status.toLowerCase()}`}>
      {tripLabels[status]}
    </span>
  );
}
export function Empty({ title, text }: { title: string; text: string }) {
  return (
    <div className="empty">
      <div className="empty-icon">↗</div>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
