import type { TokenPair, User } from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public correlation?: string,
    public retryAfter = 2,
  ) {
    super(message);
  }
}
const friendly: Record<string, string> = {
  UNAUTHENTICATED: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  FORBIDDEN: "Bạn không có quyền thực hiện thao tác này.",
  VERSION_CONFLICT:
    "Chuyến đi vừa thay đổi. Hãy kiểm tra trạng thái mới trước khi thao tác lại.",
  QUOTE_EXPIRED: "Giá đã hết hiệu lực. Vui lòng lấy giá mới.",
  INVALID_QUOTE:
    "Báo giá không còn hiệu lực. Vui lòng xem giá mới trước khi đặt chuyến.",
  QUOTE_ALREADY_USED:
    "Báo giá đã dùng cho chuyến trước. Vui lòng xem giá mới để đặt chuyến.",
  ACTIVE_TRIP_EXISTS: "Bạn đang có một chuyến đi chưa kết thúc.",
  RESERVATION_EXPIRED: "Lời mời nhận chuyến đã hết hạn.",
  STALE_ATTEMPT: "Lời mời này đã thay đổi. Vui lòng tải lại chuyến.",
  OPERATION_PENDING: "Thao tác trước đang được xử lý. Vui lòng chờ.",
  ROUTE_NOT_FOUND: "Chưa tìm được tuyến đường giữa hai địa điểm này.",
  MAP_PROVIDER_UNAVAILABLE:
    "Dịch vụ bản đồ tạm thời không phản hồi. Hãy thử lại sau.",
  MAP_RATE_LIMITED: "Dịch vụ tìm địa điểm đang bận. Vui lòng chờ một chút.",
};
export function errorText(error: unknown) {
  return error instanceof ApiError
    ? friendly[error.code] || error.message
    : error instanceof Error
      ? error.message
      : "Có lỗi xảy ra. Vui lòng thử lại.";
}

type Service = "user" | "driver" | "trip" | "pricing";
type Options = {
  method?: string;
  body?: unknown;
  key?: string;
  signal?: AbortSignal;
  auth?: boolean;
};
const STORAGE = "ride.refresh.v1";
let access = "",
  refreshToken = sessionStorage.getItem(STORAGE) || "",
  user: User | null = null;
let epoch = 0,
  refreshing: Promise<void> | null = null;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((listener) => listener());
export const session = {
  get: () => user,
  updateUser(value: User) {
    user = value;
    emit();
  },
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  accept(pair: TokenPair) {
    epoch++;
    access = pair.access_token;
    refreshToken = pair.refresh_token;
    user = pair.user;
    sessionStorage.setItem(STORAGE, refreshToken);
    emit();
  },
  clear() {
    epoch++;
    access = "";
    refreshToken = "";
    user = null;
    sessionStorage.removeItem(STORAGE);
    emit();
  },
  async restore() {
    if (refreshToken) await rotate();
  },
  async logout() {
    if (user?.roles.includes("DRIVER")) {
      try {
        await api("driver", "/api/v1/drivers/me/availability", {
          method: "PATCH",
          body: { accepting_trips: false },
          key: crypto.randomUUID(),
        });
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 404)) throw e;
      }
    }
    if (refreshToken)
      await api("user", "/api/v1/auth/logout", {
        method: "POST",
        body: { refresh_token: refreshToken },
        auth: false,
      });
    session.clear();
  },
};
async function rotate() {
  if (!refreshToken)
    throw new ApiError(401, "UNAUTHENTICATED", friendly.UNAUTHENTICATED);
  if (!refreshing) {
    const expectedEpoch = epoch,
      expectedToken = refreshToken;
    refreshing = (async () => {
      try {
        const pair = await api<TokenPair>("user", "/api/v1/auth/refresh", {
          method: "POST",
          body: { refresh_token: expectedToken },
          auth: false,
        });
        if (epoch !== expectedEpoch)
          throw new ApiError(401, "UNAUTHENTICATED", friendly.UNAUTHENTICATED);
        session.accept(pair);
      } catch (e) {
        if (
          epoch === expectedEpoch &&
          e instanceof ApiError &&
          [401, 403].includes(e.status)
        )
          session.clear();
        throw e;
      }
    })().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}
export async function api<T>(
  service: Service,
  path: string,
  options: Options = {},
  retry = true,
): Promise<T> {
  const headers: Record<string, string> = {
    "X-Correlation-ID": crypto.randomUUID(),
    Accept: "application/json",
  };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.key) headers["Idempotency-Key"] = options.key;
  if (options.auth !== false && access)
    headers.Authorization = `Bearer ${access}`;
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (options.signal?.aborted) controller.abort();
  options.signal?.addEventListener("abort", abort, { once: true });
  const timeout = setTimeout(() => controller.abort("timeout"), 15000);
  let response: Response;
  try {
    response = await fetch(`/backend/${service}${path}`, {
      method: options.method || "GET",
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
    if (
      response.status === 401 &&
      options.auth !== false &&
      retry &&
      refreshToken
    ) {
      await rotate();
      return api<T>(service, path, options, false);
    }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401 && options.auth !== false) session.clear();
      throw new ApiError(
        response.status,
        data.code || "REQUEST_FAILED",
        data.message || `Yêu cầu thất bại (${response.status}).`,
        data.correlation_id,
        Number(response.headers.get("Retry-After")) || 2,
      );
    }
    return data as T;
  } catch (e) {
    if (options.signal?.aborted || e instanceof ApiError) throw e;
    throw new ApiError(
      0,
      "NETWORK",
      "Không kết nối được dịch vụ. Kiểm tra kết nối và thử lại với cùng thao tác.",
    );
  } finally {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abort);
  }
}

/** A failed/unknown outcome keeps the same key and body, including across reloads. */
export function mutationKey(scope: string, body: unknown) {
  const storage = `ride.mutation.${user?.id || "public"}.${scope}`;
  const fingerprint = JSON.stringify(body);
  try {
    const saved = JSON.parse(sessionStorage.getItem(storage) || "null");
    if (saved?.fingerprint === fingerprint) return saved.key as string;
  } catch {
    /* New command. */
  }
  const key = crypto.randomUUID();
  sessionStorage.setItem(storage, JSON.stringify({ fingerprint, key }));
  return key;
}
export function newMutationKey(scope: string) {
  sessionStorage.removeItem(`ride.mutation.${user?.id || "public"}.${scope}`);
}
