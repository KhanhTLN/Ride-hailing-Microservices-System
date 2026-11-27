import { useEffect, useId, useState } from "react";
import { Check, LoaderCircle, MapPin, X } from "lucide-react";
import type { Place } from "../lib/types";
import { suggest } from "../lib/suggestions";
import { errorText } from "../lib/api";

export function AddressInput({
  label,
  kind,
  value,
  onChange,
}: {
  label: string;
  kind: "pickup" | "dropoff";
  value: Place | null;
  onChange: (place: Place | null) => void;
}) {
  const id = useId();
  const [query, setQuery] = useState(value?.display_name || "");
  const [places, setPlaces] = useState<Place[]>([]),
    [loading, setLoading] = useState(false),
    [error, setError] = useState("");
  const [active, setActive] = useState(-1),
    [dismissed, setDismissed] = useState(false),
    [composing, setComposing] = useState(false),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    if (
      value ||
      query.trim().length < 3 ||
      query.trim().length > 200 ||
      dismissed ||
      composing
    ) {
      setPlaces([]);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setActive(-1);
    const timer = setTimeout(async () => {
      try {
        const found = await suggest(query, controller.signal);
        if (!controller.signal.aborted) {
          setPlaces(found);
          if (!found.length)
            setError(
              "Chưa thấy địa điểm. Thử thêm tên đường, quận hoặc thành phố.",
            );
        }
      } catch (e) {
        if (!controller.signal.aborted) setError(errorText(e));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }, 450);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, value, dismissed, composing, retry]);
  const select = (place: Place) => {
    setQuery(place.display_name);
    setPlaces([]);
    setError("");
    setDismissed(true);
    onChange(place);
  };
  return (
    <div className={`address-field ${kind}`}>
      <label htmlFor={id}>{label}</label>
      <div className="address-control">
        <span className={`point-dot ${kind}`} />
        <input
          id={id}
          data-testid={kind}
          value={query}
          maxLength={200}
          placeholder={
            kind === "pickup" ? "Nhập nơi bạn muốn đón" : "Bạn muốn đến đâu?"
          }
          autoComplete="off"
          role="combobox"
          aria-autocomplete="list"
          aria-controls={`${id}-list`}
          aria-expanded={places.length > 0}
          aria-activedescendant={active < 0 ? undefined : `${id}-${active}`}
          aria-busy={loading}
          onChange={(e) => {
            setQuery(e.target.value);
            setPlaces([]);
            setError("");
            setDismissed(false);
            onChange(null);
          }}
          onCompositionStart={() => setComposing(true)}
          onCompositionEnd={() => setComposing(false)}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              setDismissed(true);
              return;
            }
            if (["ArrowDown", "ArrowUp"].includes(e.key) && places.length) {
              e.preventDefault();
              setActive((i) =>
                i < 0
                  ? e.key === "ArrowDown"
                    ? 0
                    : places.length - 1
                  : (i + (e.key === "ArrowDown" ? 1 : -1) + places.length) %
                    places.length,
              );
            }
            if (e.key === "Enter") {
              e.preventDefault();
              if (places.length) select(places[active < 0 ? 0 : active]);
              else {
                setDismissed(false);
                setRetry((v) => v + 1);
              }
            }
          }}
        />
        {loading ? (
          <LoaderCircle size={16} className="spin" />
        ) : value ? (
          <Check size={17} className="green" />
        ) : null}
        {query && (
          <button
            className="icon-button"
            type="button"
            aria-label={`Xóa ${label.toLowerCase()}`}
            onClick={() => {
              setQuery("");
              onChange(null);
              setPlaces([]);
              setError("");
            }}
          >
            <X size={15} />
          </button>
        )}
      </div>
      {places.length > 0 && (
        <ul
          className="suggestions"
          role="listbox"
          id={`${id}-list`}
          aria-label={`Gợi ý ${label.toLowerCase()}`}
        >
          {places.map((place, index) => (
            <li key={`${place.place_id}-${index}`} role="presentation">
              <button
                type="button"
                id={`${id}-${index}`}
                role="option"
                aria-selected={active === index}
                onClick={() => select(place)}
              >
                <MapPin size={17} />
                <span>
                  <strong>{place.display_name.split(",")[0]}</strong>
                  <small>
                    {place.display_name.split(",").slice(1).join(",").trim()}
                  </small>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p className="field-hint" role="status">
          {error}{" "}
          <button
            type="button"
            className="text-button"
            onClick={() => {
              setDismissed(false);
              setRetry((v) => v + 1);
            }}
          >
            Tìm lại
          </button>
        </p>
      )}
    </div>
  );
}
