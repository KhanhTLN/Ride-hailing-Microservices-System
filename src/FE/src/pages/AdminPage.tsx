import { useState } from "react";
import { CheckCircle2, FileClock, Search, ShieldCheck } from "lucide-react";
import { api, mutationKey, newMutationKey } from "../lib/api";
import { useResource } from "../lib/hooks";
import type { Driver } from "../lib/types";
import { Empty, ErrorBox, Loading } from "../components/Common";
import { when } from "../lib/format";
const labels = {
  PENDING: "Chờ duyệt",
  APPROVED: "Đã duyệt",
  SUSPENDED: "Tạm đình chỉ",
  REJECTED: "Chưa được duyệt",
};
export function AdminPage() {
  const resource = useResource(
    (signal) => api<Driver[]>("driver", "/api/v1/admin/drivers", { signal }),
    [],
    10000,
  );
  const [query, setQuery] = useState(""),
    [filter, setFilter] = useState("PENDING"),
    [selected, setSelected] = useState<Driver | null>(null),
    [target, setTarget] = useState("APPROVED"),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>();
  async function decide() {
    if (!selected || busy || !reason.trim()) return;
    setBusy(true);
    setError(undefined);
    const body = { account_status: target, reason: reason.trim() },
      scope = `approve-${selected.id}`;
    try {
      await api("driver", `/api/v1/admin/drivers/${selected.id}/status`, {
        method: "PATCH",
        body,
        key: mutationKey(scope, body),
      });
      newMutationKey(scope);
      setSelected(null);
      setReason("");
      resource.refresh();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  const drivers = resource.data?.filter(
    (d) =>
      (!filter || d.account_status === filter) &&
      `${d.license_number} ${d.user_id}`
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  return (
    <div className="content-page">
      <div className="page-heading">
        <span className="eyebrow">QUẢN LÝ ĐỐI TÁC</span>
        <h1>Hồ sơ tài xế</h1>
        <p className="muted">
          Xem xét và quản lý trạng thái các đối tác của ride.
        </p>
      </div>
      <div className="stats-grid">
        {[
          ["PENDING", "Chờ duyệt", FileClock],
          ["APPROVED", "Đang được duyệt", CheckCircle2],
          ["SUSPENDED", "Tạm đình chỉ", ShieldCheck],
        ].map(([status, title, Icon]) => {
          const Symbol = Icon as typeof FileClock;
          return (
            <div className="card stat-card" key={status as string}>
              <Symbol size={23} />
              <div>
                <strong>
                  {resource.data?.filter((d) => d.account_status === status)
                    .length ?? "—"}
                </strong>
                <span>{title as string}</span>
              </div>
            </div>
          );
        })}
      </div>
      <div className="admin-toolbar">
        <div className="search-box">
          <Search size={18} />
          <input
            aria-label="Tìm tài xế"
            placeholder="Tìm theo giấy phép hoặc mã người dùng"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <select
          aria-label="Trạng thái hồ sơ"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        >
          <option value="">Tất cả trạng thái</option>
          {Object.entries(labels).map(([key, title]) => (
            <option key={key} value={key}>
              {title}
            </option>
          ))}
        </select>
      </div>
      <ErrorBox error={resource.error} retry={resource.refresh} />
      {resource.loading && <Loading />}
      {drivers && !drivers.length && (
        <Empty
          title="Không có hồ sơ phù hợp"
          text="Hồ sơ mới sẽ xuất hiện khi tài xế gửi đăng ký."
        />
      )}
      <div className="admin-list">
        {drivers?.map((driver) => (
          <div className="card admin-row" key={driver.id}>
            <div className="avatar">{driver.license_number.slice(-2)}</div>
            <div>
              <h3>GPLX {driver.license_number}</h3>
              <small className="muted">Người dùng: {driver.user_id}</small>
              <small className="muted">
                Gửi ngày {when(driver.created_at)}
              </small>
            </div>
            <span
              className={`badge approval-${driver.account_status.toLowerCase()}`}
            >
              {labels[driver.account_status]}
            </span>
            <button
              className="secondary"
              onClick={() => {
                setSelected(driver);
                setReason("");
                setError(undefined);
                setTarget(
                  driver.account_status === "APPROVED"
                    ? "SUSPENDED"
                    : "APPROVED",
                );
              }}
            >
              Xét duyệt
            </button>
          </div>
        ))}
      </div>
      {selected && (
        <div className="modal-backdrop">
          <section
            className="modal card"
            role="dialog"
            aria-modal="true"
            aria-labelledby="approval-title"
          >
            <h2 id="approval-title">Xét duyệt hồ sơ</h2>
            <p>
              Giấy phép: <strong>{selected.license_number}</strong>
            </p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void decide();
              }}
            >
              <label>
                Quyết định
                <select
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                >
                  <option value="APPROVED">Duyệt hoạt động</option>
                  <option value="SUSPENDED">Tạm đình chỉ</option>
                </select>
              </label>
              <label>
                Lý do
                <textarea
                  autoFocus
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  required
                  maxLength={300}
                  placeholder="Nhập lý do xét duyệt"
                />
              </label>
              <ErrorBox error={error} />
              <div className="button-row">
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() => setSelected(null)}
                >
                  Quay lại
                </button>
                <button className="primary" disabled={busy || !reason.trim()}>
                  {busy ? "Đang lưu…" : "Xác nhận"}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}
    </div>
  );
}
