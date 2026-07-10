import { useEffect, useRef } from 'react';
import L from 'leaflet';
import plzCoords from './plz-coords.json';

delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images/marker-icon-2x.png',
  iconUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images/marker-icon.png',
  shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/images/marker-shadow.png',
});

export default function MapView({ shops = [], radiusData }) {
  const mapRef = useRef(null);
  const leafletMap = useRef(null);

  const getCoords = (plz) => {
    if (!plz) return [51.165, 10.45];
    const key = String(plz).trim();
    return plzCoords[key] || [51.165, 10.45];
  };

  useEffect(() => {
    if (!mapRef.current) return;

    if (!leafletMap.current) {
      leafletMap.current = L.map(mapRef.current, { zoomControl: true }).setView([51.165, 10.45], 6);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(leafletMap.current);
    }

    const map = leafletMap.current;
    map.eachLayer(layer => {
      if (layer instanceof L.Marker || layer instanceof L.Circle) map.removeLayer(layer);
    });

    // Shop markers with rich popup
    if (shops.length > 0) {
      const bounds = [];
      shops.forEach(shop => {
        const [lat, lng] = getCoords(shop.plz);
        const isAvailable = shop.products?.some(p => p.available);

        L.marker([lat, lng], { opacity: isAvailable ? 1 : 0.7 })
          .addTo(map)
          .bindPopup(`
            <div style="min-width: 240px; font-size: 13.5px;">
              <strong>${shop.name}</strong><br>
              ${shop.plz} ${shop.city || ''}<br>
              ${shop.distanceKm ? `<strong style="color:#10b981">${shop.distanceKm.toFixed(1)} km</strong>` : ''}
              <hr style="margin: 6px 0 4px 0;">
              ${shop.products?.map(p => `
                <div style="margin: 3px 0; color: ${p.available ? '#10b981' : '#64748b'}">
                  • ${p.displayName || p.product}: 
                  <strong>${p.available ? '✅ Verfügbar' : '❌ Nicht verfügbar'}</strong>
                </div>
              `).join('') || ''}
            </div>
          `);
        bounds.push([lat, lng]);
      });
      map.fitBounds(bounds, { padding: [50, 50] });
    }

    // Center PLZ + Radius Circle
    const centerPlz = radiusData?.plz || radiusData?.centerLabel || radiusData?.center || "40764";
    const km = radiusData?.km || 50;

    if (centerPlz && km > 0) {
      const [lat, lng] = getCoords(centerPlz);

      L.marker([lat, lng], {
        icon: L.divIcon({ className: 'text-3xl drop-shadow-md', html: '📍', iconSize: [32, 32] })
      }).addTo(map).bindPopup(`<strong>Dein Umkreis-Zentrum</strong><br>PLZ ${centerPlz}`);

      L.circle([lat, lng], {
        radius: km * 1000,
        color: '#10b981',
        fillColor: '#10b981',
        fillOpacity: 0.09,
        weight: 3.5,
      }).addTo(map);
    }
  }, [shops, radiusData]);

  return <div ref={mapRef} className="w-full h-[420px] rounded-xl" style={{ background: '#f8fafc' }} />;
}