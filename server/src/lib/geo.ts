/** Distance and bounding-box maths. No dependencies, no extension required. */

const EARTH_RADIUS_M = 6_371_000;

export interface Point {
  lat: number;
  lng: number;
}

/** Great-circle distance in metres. */
export function haversineM(a: Point, b: Point): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

export interface BoundingBox {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

/**
 * A box that certainly contains every point within radiusM of centre.
 * Longitude degrees shrink with latitude, hence the cosine; clamped so a
 * latitude near the poles cannot divide by zero.
 */
export function boundingBox(centre: Point, radiusM: number): BoundingBox {
  const latDelta = (radiusM / EARTH_RADIUS_M) * (180 / Math.PI);
  const cos = Math.max(Math.cos((centre.lat * Math.PI) / 180), 1e-6);
  const lngDelta = latDelta / cos;
  return {
    minLat: centre.lat - latDelta,
    maxLat: centre.lat + latDelta,
    minLng: centre.lng - lngDelta,
    maxLng: centre.lng + lngDelta,
  };
}
