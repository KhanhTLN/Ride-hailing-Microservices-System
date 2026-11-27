import { useState } from "react";
import type { FormEvent } from "react";
import {
  ArrowRight,
  Bike,
  Eye,
  EyeOff,
  Route as RouteIcon,
  ShieldCheck,
} from "lucide-react";
import { api, ApiError, mutationKey, session } from "../lib/api";
import { Brand, ErrorBox } from "../components/Common";
import type { Role, TokenPair } from "../lib/types";
import { navigate } from "../lib/hooks";
export function AuthPage() {
  const [register, setRegister] = useState(false),
    [role, setRole] = useState<Role>("PASSENGER"),
    [visible, setVisible] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>();
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(undefined);
    const form = new FormData(event.currentTarget),
      credentials = {
        email: String(form.get("email")).trim(),
        password: String(form.get("password")),
      };
    try {
      if (register) {
        const body = {
          ...credentials,
          display_name: String(form.get("name")).trim(),
          role,
        };
        // Registration fingerprint never stores the password; a retry within this form keeps a key.
        const key = mutationKey("register", {
          email: body.email,
          display_name: body.display_name,
          role,
        });
        await api("user", "/api/v1/auth/register", {
          method: "POST",
          body,
          key,
          auth: false,
        });
      }
      const pair = await api<TokenPair>("user", "/api/v1/auth/login", {
        method: "POST",
        body: credentials,
        auth: false,
      });
      session.accept(pair);
      navigate(
        pair.user.roles.includes("DRIVER")
          ? "/driver"
          : pair.user.roles.includes("ADMIN")
            ? "/admin"
            : "/book",
      );
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? new Error("Email hoặc mật khẩu chưa đúng. Vui lòng kiểm tra lại.")
          : e,
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="auth-layout">
      <section className="auth-story">
        <Brand />
        <div className="story-copy">
          <span className="eyebrow">MỖI NGÀY, MỘT HÀNH TRÌNH MỚI</span>
          <h1>
            Đi đâu
            <br />
            cũng <em>gần.</em>
          </h1>
          <p>
            Một chuyến đi nhẹ nhàng bắt đầu từ đây.
            <br />
            Chọn điểm đến, chúng tôi kết nối phần còn lại.
          </p>
          <div className="story-route">
            <span className="story-dot" />
            <div />
            <MapDestination />
          </div>
          <div className="story-features">
            <span>
              <ShieldCheck size={18} /> Giá rõ ràng
            </span>
            <span>
              <RouteIcon size={18} /> Tuyến đường thực tế
            </span>
          </div>
        </div>
        <small>
          © {new Date().getFullYear()} ride. &nbsp; Một hành trình, nhiều kết
          nối.
        </small>
      </section>
      <section className="auth-form-side">
        <div className="auth-mobile-brand">
          <Brand />
        </div>
        <div className="auth-card">
          <div className="section-icon">
            <Bike size={26} />
          </div>
          <h2>{register ? "Bắt đầu cùng ride." : "Chào bạn trở lại."}</h2>
          <p className="muted">
            {register
              ? "Tạo tài khoản cho những hành trình phía trước."
              : "Đăng nhập để tiếp tục hành trình của bạn."}
          </p>
          <form onSubmit={submit}>
            {register && (
              <>
                <div className="role-switch">
                  <button
                    type="button"
                    className={role === "PASSENGER" ? "selected" : ""}
                    onClick={() => setRole("PASSENGER")}
                  >
                    Tôi muốn đặt xe
                  </button>
                  <button
                    type="button"
                    className={role === "DRIVER" ? "selected" : ""}
                    onClick={() => setRole("DRIVER")}
                  >
                    Tôi là tài xế
                  </button>
                </div>
                <label>
                  Tên hiển thị
                  <input
                    name="name"
                    autoComplete="name"
                    placeholder="Tên của bạn"
                    required
                    maxLength={100}
                  />
                </label>
              </>
            )}
            <label>
              Email
              <input
                name="email"
                type="email"
                autoComplete="username"
                placeholder="ban@example.com"
                required
              />
            </label>
            <label>
              Mật khẩu
              <div className="password-input">
                <input
                  name="password"
                  type={visible ? "text" : "password"}
                  autoComplete={register ? "new-password" : "current-password"}
                  minLength={register ? 12 : 1}
                  maxLength={128}
                  placeholder={
                    register ? "Tối thiểu 12 ký tự" : "Nhập mật khẩu"
                  }
                  required
                />
                <button
                  type="button"
                  className="icon-button"
                  aria-label={visible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
                  onClick={() => setVisible((v) => !v)}
                >
                  {visible ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </label>
            <ErrorBox error={error} />
            <button className="primary wide" disabled={busy} type="submit">
              {busy ? "Đang xử lý…" : register ? "Tạo tài khoản" : "Đăng nhập"}
              <ArrowRight size={18} />
            </button>
          </form>
          <p className="auth-switch">
            {register ? "Đã có tài khoản?" : "Bạn mới đến ride.?"}{" "}
            <button
              className="text-button"
              onClick={() => {
                setRegister((v) => !v);
                setError(undefined);
              }}
            >
              {register ? "Đăng nhập" : "Tạo tài khoản"}
            </button>
          </p>
          <div className="auth-footnote">
            <ShieldCheck size={16} /> Đăng nhập an toàn qua tài khoản của bạn.
          </div>
        </div>
      </section>
    </div>
  );
}
function MapDestination() {
  return <span className="story-destination">↗</span>;
}
