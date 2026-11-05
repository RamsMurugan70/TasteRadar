/**
 * A sliding window of failed authentication attempts, per client.
 *
 * In memory on purpose. A restart clearing it is the correct trade for a
 * single-user server: the cost is that a restart forgives an attacker a few
 * guesses, and the benefit is no table, no cleanup job and no write amplification
 * on a database that exists to answer one question quickly.
 *
 * `now` is a parameter rather than a call to Date.now() so the behaviour over
 * time can actually be tested instead of slept through.
 */
export class FailureTracker {
  private readonly attempts = new Map<string, number[]>();

  constructor(
    private readonly maxFailures: number,
    private readonly windowMs: number,
    private readonly lockoutMs: number,
    /** Bounds memory: an attacker rotating source addresses must not grow this forever. */
    private readonly maxClients: number = 10_000,
  ) {}

  /** Milliseconds of lockout remaining, or 0 when the client may try. */
  lockedFor(key: string, now: number): number {
    const times = this.recent(key, now);
    if (times.length < this.maxFailures) return 0;
    const last = times[times.length - 1];
    if (last === undefined) return 0;
    const until = last + this.lockoutMs;
    return until > now ? until - now : 0;
  }

  record(key: string, now: number): void {
    const times = this.recent(key, now);
    times.push(now);
    this.attempts.set(key, times);

    if (this.attempts.size > this.maxClients) {
      // Drop the least recently active client. Map preserves insertion order,
      // and `recent` re-inserts on access, so the first key is the stalest.
      const oldest = this.attempts.keys().next();
      if (!oldest.done) this.attempts.delete(oldest.value);
    }
  }

  /** A correct token forgives the failures before it. */
  clear(key: string): void {
    this.attempts.delete(key);
  }

  get trackedClients(): number {
    return this.attempts.size;
  }

  private recent(key: string, now: number): number[] {
    const times = (this.attempts.get(key) ?? []).filter((t) => t > now - this.windowMs);
    this.attempts.delete(key);
    if (times.length > 0) this.attempts.set(key, times);
    return times;
  }
}
