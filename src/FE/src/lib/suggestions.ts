import { api, ApiError } from "./api";
import type { Place } from "./types";
const cache = new Map<string, Place[]>();
let nextAt = 0;
function wait(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    const abort = () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    signal.addEventListener("abort", abort, { once: true });
  });
}
export async function suggest(
  query: string,
  signal: AbortSignal,
): Promise<Place[]> {
  const key = query.trim().toLocaleLowerCase().replace(/\s+/g, " ");
  for (let attempt = 0; attempt < 2; attempt++) {
    while (Date.now() < nextAt && !cache.has(key))
      await wait(nextAt - Date.now(), signal);
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (cache.has(key)) return cache.get(key)!;
    nextAt = Date.now() + 1200;
    try {
      const places = await api<Place[]>(
        "pricing",
        `/api/v1/maps/suggestions?q=${encodeURIComponent(query.trim())}`,
        { signal },
      );
      if (cache.size >= 50) cache.delete(cache.keys().next().value!);
      cache.set(key, places);
      return places;
    } catch (e) {
      if (e instanceof ApiError && e.status === 429 && attempt === 0)
        nextAt = Math.max(nextAt, Date.now() + e.retryAfter * 1000);
      else throw e;
    }
  }
  return [];
}
