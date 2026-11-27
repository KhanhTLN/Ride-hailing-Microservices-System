import { useEffect, useState } from "react";
import {
  Bike,
  CircleUserRound,
  History,
  LayoutDashboard,
  LifeBuoy,
  LogOut,
  Menu,
  Navigation,
  ShieldCheck,
  X,
} from "lucide-react";
import { session } from "./lib/api";
import { navigate, useHash, useUser } from "./lib/hooks";
import { roleLabel } from "./lib/format";
import { PresenceProvider, usePresence } from "./lib/presence";
import { Brand, Empty, ErrorBox, Loading } from "./components/Common";
import { AuthPage } from "./pages/AuthPage";
import { BookingPage, RebookingPage } from "./pages/BookingPage";
import { HistoryPage } from "./pages/HistoryPage";
import { TripPage } from "./pages/TripPage";
import { DriverPage } from "./pages/DriverPage";
import { AdminPage } from "./pages/AdminPage";
import { AccountPage } from "./pages/AccountPage";
import { HelpPage } from "./pages/HelpPage";

export default function App() {
  const user = useUser();
  const [loading, setLoading] = useState(true),
    [error, setError] = useState<unknown>();
  useEffect(() => {
    session
      .restore()
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);
  if (loading)
    return (
      <div className="boot">
        <Brand />
        <Loading text="Đang chuẩn bị hành trình…" />
      </div>
    );
  if (!user)
    return (
      <>
        {error && (
          <div className="restore-error">
            <ErrorBox error={error} />
          </div>
        )}
        <AuthPage />
      </>
    );
  return (
    <PresenceProvider key={user.id}>
      <Shell />
    </PresenceProvider>
  );
}
function Shell() {
  const presence = usePresence();
  const user = useUser()!,
    path = useHash(),
    driver = user.roles.includes("DRIVER"),
    admin = user.roles.includes("ADMIN");
  const [menu, setMenu] = useState(false),
    [error, setError] = useState<unknown>(),
    [loggingOut, setLoggingOut] = useState(false);
  const home = admin ? "/admin" : driver ? "/driver" : "/book";
  useEffect(() => {
    if (path === "/" || path === "/login") navigate(home);
    setMenu(false);
    window.scrollTo(0, 0);
  }, [path, home]);
  const links = admin
    ? [
        [home, "Duyệt tài xế", ShieldCheck],
        ["/account", "Tài khoản", CircleUserRound],
      ]
    : [
        [
          home,
          driver ? "Không gian tài xế" : "Đặt chuyến",
          driver ? LayoutDashboard : Navigation,
        ],
        ["/trips", "Chuyến đi", History],
        ["/account", "Tài khoản", CircleUserRound],
      ];
  let content;
  if (path === home || path === "/")
    content = admin ? <AdminPage /> : driver ? <DriverPage /> : <BookingPage />;
  else if (/^\/book\/rebook\/[a-f0-9-]{36}$/i.test(path) && home === "/book")
    content = <RebookingPage key={path} tripId={path.split("/")[3]} />;
  else if (path === "/trips" && !admin) content = <HistoryPage />;
  else if (/^\/trips\/[a-f0-9-]{36}$/i.test(path) && !admin)
    content = <TripPage key={path} id={path.split("/")[2]} />;
  else if (path === "/account") content = <AccountPage />;
  else if (path === "/help") content = <HelpPage />;
  else
    content = (
      <div className="content-page">
        <Empty
          title="Trang này chưa có ở đây"
          text="Chọn một mục trong menu để tiếp tục hành trình."
        />
        <a className="primary" href={`#${home}`}>
          Về trang chính
        </a>
      </div>
    );
  return (
    <div className="app-layout">
      <aside className={`sidebar ${menu ? "open" : ""}`}>
        <Brand />
        <button
          className="mobile-close icon-button"
          aria-label="Đóng menu"
          onClick={() => setMenu(false)}
        >
          <X />
        </button>
        <div className="workspace-label">
          {admin
            ? "QUẢN TRỊ"
            : driver
              ? "DÀNH CHO ĐỐI TÁC"
              : "HÀNH TRÌNH CỦA BẠN"}
        </div>
        <nav aria-label="Điều hướng chính">
          {links.map(([href, title, Icon]) => {
            const Symbol = Icon as typeof Bike;
            return (
              <a
                key={href as string}
                className={
                  path === href ||
                  (href === "/trips" && path.startsWith("/trips/"))
                    ? "active"
                    : ""
                }
                href={`#${href}`}
              >
                <Symbol size={20} />
                {title as string}
              </a>
            );
          })}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-message">
            <span>
              Đi nhẹ nhàng.
              <br />
              Đến an tâm.
            </span>
            <span className="route-doodle">↗</span>
          </div>
          <a
            className={`help-link ${path === "/help" ? "active" : ""}`}
            href="#/help"
          >
            <LifeBuoy size={19} /> Hướng dẫn & hỗ trợ
          </a>
          <div className="sidebar-user">
            <span className="avatar">
              {user.display_name.slice(0, 1).toUpperCase()}
            </span>
            <div>
              <strong>{user.display_name}</strong>
              <small>{roleLabel(user.roles[0])}</small>
            </div>
            <button
              className="icon-button"
              aria-label="Đăng xuất"
              title="Đăng xuất"
              disabled={loggingOut || presence.busy}
              onClick={async () => {
                setLoggingOut(true);
                setError(undefined);
                try {
                  await session.logout();
                  navigate("/login");
                } catch (e) {
                  setError(e);
                } finally {
                  setLoggingOut(false);
                }
              }}
            >
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </aside>
      {menu && (
        <button
          className="menu-scrim"
          onClick={() => setMenu(false)}
          aria-label="Đóng menu"
        />
      )}
      <div className="workspace">
        <header className="topbar">
          <button
            className="icon-button mobile-menu"
            onClick={() => setMenu(true)}
            aria-label="Mở menu"
          >
            <Menu />
          </button>
          <span className="topbar-title">
            {admin
              ? "Quản lý đối tác"
              : driver
                ? "Cùng ride. kết nối mọi hành trình"
                : "Một ngày tốt lành, một chuyến đi vui."}
          </span>
          <a href="#/account" className="topbar-user">
            <span className="online-dot" />
            {user.display_name}
            <span className="avatar small">
              {user.display_name.slice(0, 1).toUpperCase()}
            </span>
          </a>
        </header>
        {!!error && (
          <div className="shell-error">
            <ErrorBox error={error} />
          </div>
        )}
        <main>{content}</main>
      </div>
    </div>
  );
}
