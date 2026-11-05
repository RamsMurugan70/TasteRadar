import type { FastifyInstance } from 'fastify';
import type { Db } from '../db.js';
import { candidateFences, recordFenceSet } from '../db.js';
import { config } from '../config.js';

/**
 * A row the worker wrote badly must not blind the phone to every other fence, so
 * unparseable hours degrade to unknown rather than failing the request. Unknown
 * never suppresses an alert, so the cost of this is an alert about a place that
 * may be shut - far cheaper than no alerts at all.
 */
function parseHours(raw: string | null): unknown {
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

interface FenceSetBody {
  lat: number;
  lng: number;
}

export function fenceRoutes(app: FastifyInstance, db: Db): void {
  /**
   * The one endpoint the phone actually depends on. It is called when the
   * umbrella fence is crossed, which on a normal day is three or four times.
   */
  app.post<{ Body: FenceSetBody }>(
    '/fence-set',
    {
      schema: {
        body: {
          type: 'object',
          required: ['lat', 'lng'],
          properties: {
            lat: { type: 'number', minimum: -90, maximum: 90 },
            lng: { type: 'number', minimum: -180, maximum: 180 },
          },
        },
      },
    },
    async (request) => {
      const centre = { lat: request.body.lat, lng: request.body.lng };
      recordFenceSet(db, config.defaultUserId, centre);
      const fences = candidateFences(db, config.defaultUserId, centre);

      return {
        umbrella: { lat: centre.lat, lng: centre.lng, radiusM: config.umbrellaRadiusM },
        fenceRadiusM: 150,
        fences: fences.map((f) => ({
          placeId: f.placeId,
          wantId: f.wantId,
          name: f.name,
          lat: f.lat,
          lng: f.lng,
          score: f.score,
          reason: f.reason,
          distanceM: f.distanceM,
          seenBefore: f.lastAlertedAt !== null,
          // Sent raw, and evaluated on the phone at alert time rather than here.
          // A fence set is drawn once and then used for hours, so "open now"
          // computed at this moment would be stale by the time it mattered.
          hours: parseHours(f.hours),
          // Sent with the hours, because hours without their zone are a guess.
          timezone: f.timezone,
        })),
      };
    },
  );
}
