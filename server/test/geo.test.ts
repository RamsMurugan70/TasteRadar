import { test } from 'node:test';
import assert from 'node:assert/strict';
import { haversineM, boundingBox } from '../src/lib/geo.js';

const nungambakkam = { lat: 13.0604, lng: 80.2496 };
const triplicane = { lat: 13.0569, lng: 80.274 };

test('haversine matches a known Chennai distance', () => {
  // Nungambakkam to Triplicane is about 2.7 km as the crow flies.
  const d = haversineM(nungambakkam, triplicane);
  assert.ok(d > 2500 && d < 2900, `expected ~2.7km, got ${Math.round(d)}m`);
});

test('haversine is zero for the same point and symmetric', () => {
  assert.equal(haversineM(nungambakkam, nungambakkam), 0);
  assert.ok(
    Math.abs(haversineM(nungambakkam, triplicane) - haversineM(triplicane, nungambakkam)) < 1e-6,
  );
});

test('bounding box contains every point inside the radius', () => {
  const radius = 5000;
  const box = boundingBox(nungambakkam, radius);
  // Walk the circle and check the box never excludes a point it should contain.
  for (let deg = 0; deg < 360; deg += 5) {
    const rad = (deg * Math.PI) / 180;
    const latDelta = (radius / 6_371_000) * (180 / Math.PI) * Math.cos(rad);
    const lngDelta =
      ((radius / 6_371_000) * (180 / Math.PI) * Math.sin(rad)) /
      Math.cos((nungambakkam.lat * Math.PI) / 180);
    const p = { lat: nungambakkam.lat + latDelta, lng: nungambakkam.lng + lngDelta };
    assert.ok(
      p.lat >= box.minLat && p.lat <= box.maxLat && p.lng >= box.minLng && p.lng <= box.maxLng,
      `point at ${deg}deg fell outside the box`,
    );
  }
});

test('bounding box over-selects, so the exact filter still matters', () => {
  const box = boundingBox(nungambakkam, 1000);
  const corner = { lat: box.maxLat, lng: box.maxLng };
  // The corner of the box is further than the radius - this is why
  // candidateFences re-filters with haversine instead of trusting the box.
  assert.ok(haversineM(nungambakkam, corner) > 1000);
});
