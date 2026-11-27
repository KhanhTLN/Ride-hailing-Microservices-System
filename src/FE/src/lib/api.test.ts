import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
const pair = (access = "old", refresh = "refresh-1") => ({
  access_token: access,
  refresh_token: refresh,
  user: {
    id: "user-1",
    email: "rider@example.test",
    display_name: "Rider",
    roles: ["PASSENGER"],
    created_at: "2026-01-01T00:00:00Z",
  },
  expires_in: 900,
});
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
beforeEach(() => {
  vi.resetModules();
  const values = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  });
});
afterEach(() => vi.unstubAllGlobals());
describe("API session and mutation behavior", () => {
  it("rotates refresh token once for concurrent unauthorized requests", async () => {
    let refreshCalls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        if (url.includes("/refresh")) {
          refreshCalls++;
          await new Promise((resolve) => setTimeout(resolve, 20));
          return response(pair("new", "refresh-2"));
        }
        return (init.headers as Record<string, string>).Authorization ===
          "Bearer new"
          ? response({ ok: true })
          : response({ code: "UNAUTHENTICATED" }, 401);
      }),
    );
    const { api, session } = await import("./api");
    session.accept(pair() as never);
    const results = await Promise.all([
      api("trip", "/first"),
      api("driver", "/second"),
    ]);
    expect(results).toEqual([{ ok: true }, { ok: true }]);
    expect(refreshCalls).toBe(1);
    expect(sessionStorage.getItem("ride.refresh.v1")).toBe("refresh-2");
  });
  it("cannot resurrect a logged-out session from a late refresh response", async () => {
    let release!: (value: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.includes("/refresh")
          ? new Promise<Response>((resolve) => {
              release = resolve;
            })
          : response({ code: "UNAUTHENTICATED" }, 401),
      ),
    );
    const { api, session } = await import("./api");
    session.accept(pair() as never);
    const pending = api("trip", "/first");
    await vi.waitFor(() => expect(release).toBeTypeOf("function"));
    session.clear();
    release(response(pair("new", "refresh-2")));
    await expect(pending).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toBeNull();
    expect(sessionStorage.getItem("ride.refresh.v1")).toBeNull();
  });
  it("replays a mutation after refresh with exactly the same body/key", async () => {
    const mutations: RequestInit[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        if (url.includes("/refresh")) return response(pair("new", "refresh-2"));
        mutations.push(init);
        return mutations.length === 1
          ? response({ code: "UNAUTHENTICATED" }, 401)
          : response({ id: "same-trip" }, 201);
      }),
    );
    const { api, session } = await import("./api");
    session.accept(pair() as never);
    await api("trip", "/api/v1/trips", {
      method: "POST",
      body: { quote_id: "quote-1", payment_method: "SIMULATED" },
      key: "fixed-key",
    });
    expect(mutations).toHaveLength(2);
    expect(mutations[0].body).toBe(mutations[1].body);
    expect(
      (mutations[0].headers as Record<string, string>)["Idempotency-Key"],
    ).toBe((mutations[1].headers as Record<string, string>)["Idempotency-Key"]);
  });
  it("does not retry writes automatically after an unknown server outcome", async () => {
    const fetcher = vi.fn(async () =>
      response({ code: "INTERNAL", message: "Unavailable" }, 503),
    );
    vi.stubGlobal("fetch", fetcher);
    const { api } = await import("./api");
    await expect(
      api("trip", "/create", { method: "POST", body: {}, key: "same-key" }),
    ).rejects.toMatchObject({ status: 503 });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("retains a key until request fingerprint changes, and scopes it by account", async () => {
    const { mutationKey, session } = await import("./api");
    session.accept(pair() as never);
    const key = mutationKey("book", { quote_id: "first" });
    expect(mutationKey("book", { quote_id: "first" })).toBe(key);
    expect(mutationKey("book", { quote_id: "second" })).not.toBe(key);
    session.accept({
      ...pair(),
      user: { ...pair().user, id: "user-2" },
    } as never);
    expect(mutationKey("book", { quote_id: "first" })).not.toBe(key);
  });
});
