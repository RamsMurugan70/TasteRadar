import type { FastifyInstance } from 'fastify';
import type { Db } from '../db.js';
import { candidateFences, recordFenceSet } from '../db.js';
import { config } from '../config.js';

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
        })),
      };
    },
  );
}
