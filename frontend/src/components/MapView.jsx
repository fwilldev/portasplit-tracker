import { useEffect, useRef } from 'react';
import L from 'leaflet';
// Bundle Leaflet's default marker images locally (Vite rewrites these to hashed asset URLs)
// instead of pulling them from a third-party CDN at runtime.
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});

// Geographic centre of Germany — only used as the initial view before markers set the bounds.
const GERMANY_CENTER = [51.165, 10.45];

export default function MapView({ shops = [], radiusData }) {
  const mapRef = useRef(null);
  const leafletMap = useRef(null);

  useEffect(() => {
    if (!mapRef.current) return;

    if (!leafletMap.current) {
      leafletMap.current = L.map(mapRef.current, { zoomControl: true }).setView(GERMANY_CENTER, 6);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap',
      }).addTo(leafletMap.current);
    }

    const map = leafletMap.current;
    map.eachLayer(layer => {
      if (layer instanceof L.Marker || layer instanceof L.Circle) map.removeLayer(layer);
    });

    const bounds = [];

    // Shop markers with rich popup — only shops we have real coordinates for.
    shops.forEach(shop => {
      if (shop.lat == null || shop.lon == null) return;
      const isAvailable = shop.products?.some(p => p.available);

      L.marker([shop.lat, shop.lon], { opacity: isAvailable ? 1 : 0.7 })
        .addTo(map)
        .bindPopup(`
          <div style="min-width: 240px; font-size: 13.5px;">
            <strong>${shop.name}</strong><br>
            ${shop.plz || ''} ${shop.city || ''}<br>
            ${shop.distanceKm != null ? `<strong style="color:#10b981">${shop.distanceKm.toFixed(1)} km</strong>` : ''}
            <hr style="margin: 6px 0 4px 0;">
            ${shop.products?.map(p => `
              <div style="margin: 3px 0; color: ${p.available ? '#10b981' : '#64748b'}">
                • ${p.displayName || p.product}:
                <strong>${p.available ? '✅ Verfügbar' : '❌ Nicht verfügbar'}</strong>
              </div>
            `).join('') || ''}
          </div>
        `);
      bounds.push([shop.lat, shop.lon]);
    });

    // Radius centre marker + circle, using the exact coordinates from the backend.
    const { centerLat, centerLon, km, centerLabel } = radiusData || {};
    if (centerLat != null && centerLon != null) {
      L.marker([centerLat, centerLon], {
        icon: L.divIcon({ className: 'text-3xl drop-shadow-md', html: '📍', iconSize: [32, 32] }),
      }).addTo(map).bindPopup(`<strong>Dein Umkreis-Zentrum</strong><br>${centerLabel || ''}`);

      if (km > 0) {
        L.circle([centerLat, centerLon], {
          radius: km * 1000,
          color: '#10b981',
          fillColor: '#10b981',
          fillOpacity: 0.09,
          weight: 3.5,
        }).addTo(map);
      }
      bounds.push([centerLat, centerLon]);
    }

    if (bounds.length > 0) map.fitBounds(bounds, { padding: [50, 50] });
  }, [shops, radiusData]);

  return <div ref={mapRef} className="w-full h-[420px] rounded-xl" style={{ background: '#f8fafc' }} />;
}
