import Anthropic from '@anthropic-ai/sdk';

/** Extraction runs once per want, never in the alert path. */
const EXTRACT_MODEL = 'claude-haiku-5-5';

export interface Criteria {
  category: 'food' | 'shopping' | 'other';
  place_types: string[];
  keywords: string[];
  exclusions: string[];
  price_band: 'any' | 'cheap' | 'moderate' | 'expensive';
  min_rating: number;
}

export interface Extraction {
  criteria: Criteria;
  restated: string;
  /** 'model' when Claude read the sentence, 'keywords' when no key was set. */
  via: 'model' | 'keywords';
}

const PROMPT = (want: string, negatives: string[]) => `Turn this spoken want into match criteria for a places database.

Want: ${want}
${negatives.length ? `\nPlaces this person already rejected for this kind of want: ${negatives.join(', ')}\n` : ''}
Reply with JSON only, no prose, in exactly this shape:
{"category": "food|shopping|other",
 "place_types": ["google place types that would match"],
 "keywords": ["words that would appear in a review of a good match"],
 "exclusions": ["words that rule a place out"],
 "price_band": "any|cheap|moderate|expensive",
 "min_rating": 4.0,
 "restated": "one short line restating the want for the user to confirm"}`;

export function parseJsonObject(text: string): Record<string, unknown> {
  const stripped = text.replace(/```(?:json)?/g, '').trim();
  const start = stripped.indexOf('{');
  const end = stripped.lastIndexOf('}');
  if (start === -1 || end === -1) throw new Error(`no JSON object in reply: ${text.slice(0, 200)}`);
  return JSON.parse(stripped.slice(start, end + 1)) as Record<string, unknown>;
}

/**
 * The no-key fallback. Crude on purpose: it keeps the API usable without a
 * credential, and it is the baseline the model has to beat.
 */
export function keywordExtraction(rawText: string): Extraction {
  const lower = rawText.toLowerCase();
  const stop = new Set([
    'good', 'near', 'nearby', 'under', 'want', 'need', 'some', 'looking', 'craving',
    'proper', 'really', 'something', 'place', 'places', 'with', 'that', 'this',
  ]);
  const keywords = [...new Set(lower.match(/[a-z]{4,}/g) ?? [])].filter((w) => !stop.has(w));
  const shopping = /\b(jacket|shoes|shirt|buy|shop|store|size)\b/.test(lower);
  const price_band: Criteria['price_band'] = /\b(cheap|budget|inexpensive)\b/.test(lower)
    ? 'cheap'
    : /\b(expensive|fine dining|upscale)\b/.test(lower)
      ? 'expensive'
      : 'any';

  return {
    criteria: {
      category: shopping ? 'shopping' : 'food',
      place_types: shopping ? ['store'] : ['restaurant'],
      keywords,
      exclusions: [],
      price_band,
      min_rating: 4.0,
    },
    restated: `${keywords.join(', ')} (read without a model)`,
    via: 'keywords',
  };
}

export async function extractCriteria(
  rawText: string,
  negatives: string[] = [],
): Promise<Extraction> {
  if (!process.env.ANTHROPIC_API_KEY) return keywordExtraction(rawText);

  const client = new Anthropic();
  const message = await client.messages.create({
    model: EXTRACT_MODEL,
    max_tokens: 2000,
    messages: [{ role: 'user', content: PROMPT(rawText, negatives) }],
  });

  const text = message.content
    .filter((block): block is Anthropic.TextBlock => block.type === 'text')
    .map((block) => block.text)
    .join('');

  const parsed = parseJsonObject(text);
  const { restated, ...criteria } = parsed;
  return {
    criteria: criteria as unknown as Criteria,
    restated: typeof restated === 'string' ? restated : rawText,
    via: 'model',
  };
}
