import { useState } from "react";
import { CheckCircle2, Mail, ShieldCheck, UserRound } from "lucide-react";
import { api, session } from "../lib/api";
import { useUser } from "../lib/hooks";
import { roleLabel, when } from "../lib/format";
import { ErrorBox } from "../components/Common";
import type { User } from "../lib/types";
export function AccountPage() {
  const user = useUser()!,
    [email, setEmail] = useState(user.email),
    [busy, setBusy] = useState(false),
    [saved, setSaved] = useState(false),
    [error, setError] = useState<unknown>();
  return (
    <div className="content-page account-page">
      <div className="page-heading">
        <span className="eyebrow">THÔNG TIN CỦA BẠN</span>
        <h1>Tài khoản</h1>
        <p className="muted">Một tài khoản cho mọi hành trình.</p>
      </div>
      <div className="account-grid">
        <section className="card profile-summary">
          <div className="avatar large">
            {user.display_name.slice(0, 1).toUpperCase()}
          </div>
          <h2>{user.display_name}</h2>
          <span className="badge">{roleLabel(user.roles[0])}</span>
          <p className="muted">Tham gia từ {when(user.created_at)}</p>
          <div className="profile-meta">
            <UserRound size={18} />
            <span>
              Mã tài khoản<small>{user.id}</small>
            </span>
          </div>
        </section>
        <section className="card">
          <h2>Thông tin liên hệ</h2>
          <p className="muted">
            Email này được dùng để đăng nhập vào tài khoản của bạn.
          </p>
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              setBusy(true);
              setError(undefined);
              setSaved(false);
              try {
                const result = await api<{ data: User }>(
                  "user",
                  `/api/user/${user.id}`,
                  { method: "PATCH", body: { email: email.trim() } },
                );
                session.updateUser(result.data);
                setSaved(true);
              } catch (e) {
                setError(e);
              } finally {
                setBusy(false);
              }
            }}
          >
            <label>
              Email
              <div className="input-icon">
                <Mail size={17} />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    setSaved(false);
                  }}
                  required
                />
              </div>
            </label>
            <ErrorBox error={error} />
            {saved && (
              <p className="success-note" role="status">
                <CheckCircle2 size={17} /> Đã cập nhật email.
              </p>
            )}
            <button
              className="primary"
              disabled={busy || email.trim() === user.email}
            >
              {busy ? "Đang lưu…" : "Lưu thay đổi"}
            </button>
          </form>
          <div className="account-security">
            <ShieldCheck size={21} />
            <div>
              <strong>Phiên đăng nhập trên trình duyệt này</strong>
              <p className="muted">
                Đăng xuất khi sử dụng xong trên thiết bị dùng chung.
              </p>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
