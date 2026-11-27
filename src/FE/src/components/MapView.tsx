import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { Layers, MapPin } from "lucide-react";
import { api } from "../lib/api";
import type { MapsConfig, Point, Route } from "../lib/types";
export function MapView({
  pickup,
  dropoff,
  geometry,
  driver,
}: {
  pickup?: Point | null;
  dropoff?: Point | null;
  geometry?: Route["geometry"];
  driver?: Point | null;
}) {
  const element = useRef<HTMLDivElement>(null),
    map = useRef<L.Map | null>(null),
    group = useRef<L.FeatureGroup | null>(null);
  const [tileError, setTileError] = useState(false);
  useEffect(() => {
    if (!element.current) return;
    const instance = L.map(element.current, {
      zoomControl: false,
      zoomAnimation: false,
      fadeAnimation: false,
      markerZoomAnimation: false,
    }).setView([21.0285, 105.8542], 13);
    map.current = instance;
    L.control.zoom({ position: "bottomright" }).addTo(instance);
    group.current = L.featureGroup().addTo(instance);
    const observer = new ResizeObserver(() => instance.invalidateSize());
    observer.observe(element.current);
    const controller = new AbortController();
    let loaded = 0;
    api<MapsConfig>("pricing", "/maps/config", {
      auth: false,
      signal: controller.signal,
    })
      .then((config) => {
        if (controller.signal.aborted) return;
        L.tileLayer(config.tile_url, {
          maxZoom: 19,
          attribution:
            '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        })
          .on("loading", () => {
            loaded = 0;
          })
          .on("tileload", () => {
            loaded++;
            setTileError(false);
          })
          .on("tileerror", () => {
            if (!loaded) setTileError(true);
          })
          .addTo(instance);
        instance.attributionControl.addAttribution(
          'Routing: <a href="https://project-osrm.org/">OSRM</a>',
        );
      })
      .catch(() => {
        if (!controller.signal.aborted) setTileError(true);
      });
    return () => {
      controller.abort();
      observer.disconnect();
      instance.stop();
      instance.remove();
      map.current = null;
      group.current = null;
    };
  }, []);
  useEffect(() => {
    const instance = map.current,
      layer = group.current;
    if (!instance || !layer) return;
    layer.clearLayers();
    const add = (
      point: Point | null | undefined,
      label: string,
      kind: string,
    ) => {
      if (!point) return;
      const marker = L.marker([point.latitude, point.longitude], {
        icon: L.divIcon({
          className: `ride-pin ${kind}`,
          html: label,
          iconSize: [34, 34],
          iconAnchor: [17, 17],
        }),
      });
      const text = document.createElement("span");
      text.textContent =
        point.address ||
        (kind === "driver"
          ? "Vị trí của bạn"
          : label === "P"
            ? "Điểm đón"
            : "Điểm đến");
      marker.bindPopup(text);
      layer.addLayer(marker);
    };
    add(pickup, "P", "pickup");
    add(dropoff, "D", "dropoff");
    add(driver, "↗", "driver");
    if (geometry)
      layer.addLayer(
        L.geoJSON(geometry, {
          style: { color: "#137a56", weight: 6, opacity: 0.85 },
        }),
      );
    const bounds = layer.getBounds();
    if (bounds.isValid())
      instance.fitBounds(bounds, { padding: [65, 65], maxZoom: 16 });
  }, [
    pickup?.latitude,
    pickup?.longitude,
    dropoff?.latitude,
    dropoff?.longitude,
    driver?.latitude,
    driver?.longitude,
    geometry,
  ]);
  return (
    <div className="map-shell">
      <div className="map-canvas" ref={element} data-testid="map" />
      <div className="map-label">
        <Layers size={15} /> Bản đồ hành trình
      </div>
      {tileError && (
        <div className="map-warning" role="status">
          <MapPin size={18} />
          <span>
            Chưa tải được ảnh nền. Kiểm tra Internet hoặc DNS; bạn vẫn có thể
            tìm địa điểm và đặt chuyến.
          </span>
        </div>
      )}
    </div>
  );
}
