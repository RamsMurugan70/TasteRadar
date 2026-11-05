/**
 * Every threshold the matching depends on, in one place and read from the
 * environment, so they can be tuned on the server without shipping an app
 * build. Assume the first set of numbers is wrong.
 */
const num = (name: string, fallback: number): number => {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) throw new Error(`${name} must be a number, got "${raw}"`);
  return parsed;
};

export const config = {
  port: num('PORT', 5090),
  databaseFile: process.env.DATABASE_FILE ?? './taste-radar.db',

  /** The one fence that is always on. Leaving it is what triggers a refresh. */
  umbrellaRadiusM: num('UMBRELLA_RADIUS_M', 3000),

  /** How far out to look for matches when drawing a new set. */
  candidateRadiusM: num('CANDIDATE_RADIUS_M', 5000),

  /** Android allows 100. Fifteen is a battery decision, not a platform limit. */
  maxFences: num('MAX_FENCES', 15),

  /** Below this, a place is not worth interrupting you for. */
  scoreThreshold: num('SCORE_THRESHOLD', 70),

  /** Same place at most once in this window. */
  cooldownDays: num('COOLDOWN_DAYS', 30),

  /** Longer silence after you actively dismissed it. */
  notNowDays: num('NOT_NOW_DAYS', 90),

  /** You already know what is near home and work. */
  familiarRadiusM: num('FAMILIAR_RADIUS_M', 300),

  /** Single-user install: one row in users, created on first boot. */
  defaultUserId: process.env.DEFAULT_USER_ID ?? 'me',

  /**
   * Shared bearer token. Every request must carry it.
   *
   * Leave it unset and the server binds to loopback only - see bindHost. That
   * way "no token" cannot accidentally mean "open to the whole network", which
   * is the mistake a warning in a log file does not prevent.
   */
  authToken: process.env.AUTH_TOKEN ?? '',

  get bindHost(): string {
    if (process.env.BIND_HOST) return process.env.BIND_HOST;
    return this.authToken ? '0.0.0.0' : '127.0.0.1';
  },
} as const;
