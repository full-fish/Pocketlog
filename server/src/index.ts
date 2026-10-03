// Pocketlog AI proxy: keeps the Anthropic API key off the phone.
// The app (app/src/main/java/.../ai/Ai.kt) calls POST /scan, /parse, /categorize with header x-app-token.
// ponytail: one shared app token; per-user quotas (KV/D1) come with the P1 store release.
import Anthropic from "@anthropic-ai/sdk";

interface Env {
  ANTHROPIC_API_KEY: string;
  APP_TOKEN: string;
}

const MODEL = "claude-opus-5-5";

class BadInput extends Error {}
class Refused extends Error {}

const nullable = (type: string) => ({ type: [type, "null"] });

const SCAN_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["source_app", "document_type", "transactions", "warnings"],
  properties: {
    source_app: { type: "string" },
    document_type: { type: "string", enum: ["order_list", "order_detail", "receipt", "payment_history", "bank_history", "other"] },
    transactions: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["date", "time", "merchant", "total_amount", "currency", "payment_hint", "status", "items", "shipping_fee", "discount", "confidence"],
        properties: {
          date: nullable("string"),
          time: nullable("string"),
          merchant: { type: "string" },
          total_amount: { type: "integer" },
          currency: { type: "string" },
          payment_hint: nullable("string"),
          status: { type: "string", enum: ["paid", "canceled", "refunded", "partially_refunded"] },
          items: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              required: ["name", "quantity", "amount", "category_id"],
              properties: {
                name: { type: "string" },
                quantity: { type: "integer" },
                amount: { type: "integer" },
                category_id: nullable("string"),
              },
            },
          },
          shipping_fee: { type: "integer" },
          discount: { type: "integer" },
          confidence: { type: "number" },
        },
      },
    },
    warnings: { type: "array", items: { type: "string", enum: ["image_cut_off", "blurry", "total_mismatch"] } },
  },
};

const SCAN_SYSTEM = `You read screenshots from Korean shopping, delivery, payment and banking apps (or photos of paper receipts) and extract the user's own purchases for a household budget app.

- One transaction per order or payment, not per item. Put the order's line items in items.
- Amounts are integers in the currency's main unit (KRW: whole won). Use the price actually paid for each item as shown. If items have no individual price, use one item with the order total.
- total_amount is what was paid for the order. shipping_fee and discount are order-level amounts shown on screen, otherwise 0.
- date is the order or payment date as YYYY-MM-DD. Screens often omit the year: use the year of today, unless that date would be after today, then use the previous year. time is HH:MM if shown, otherwise null.
- merchant is the store or platform name, e.g. "쿠팡" or "배달의민족 · 교촌치킨".
- payment_hint is the payment method exactly as shown (e.g. "쿠팡머니", "쿠페이 머니", "삼성카드"), otherwise null.
- status is canceled, refunded or partially_refunded when the screen says 취소, 반품 or 환불; otherwise paid.
- category_id is the best id from the given categories for each item, or null if nothing fits. The user's hints show how they categorize things.
- The images can be consecutive slices of one long screenshot with overlap. Never report the same order twice.
- Ignore ads, recommended products, coupons and anything that is not the user's own purchase.
- confidence is 0 to 1, lower when text is cut off or hard to read.
- source_app is one of coupang, naver, kurly, baemin, yogiyo, musinsa, 11st, gmarket, toss, kakaopay, naverpay, bank, card, receipt, other.
- If there are no purchases, return an empty transactions list.`;

const PARSE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["is_transaction", "kind", "amount_krw", "foreign_amount", "merchant", "installment_months", "month", "day", "hour", "minute", "issuer", "pay_kind", "card_last4", "is_top_up"],
  properties: {
    is_transaction: { type: "boolean" },
    kind: { type: "string", enum: ["spend", "cancel", "withdraw", "deposit"] },
    amount_krw: nullable("integer"),
    foreign_amount: nullable("string"),
    merchant: { type: "string" },
    installment_months: { type: "integer" },
    month: nullable("integer"),
    day: nullable("integer"),
    hour: nullable("integer"),
    minute: nullable("integer"),
    issuer: nullable("string"),
    pay_kind: { type: "string", enum: ["credit", "check", "bank", "pay_money"] },
    card_last4: nullable("string"),
    is_top_up: { type: "boolean" },
  },
};

const PARSE_SYSTEM = `You read one Korean card, bank or payment-app notification and extract the transaction for a household budget app. Personal data in it was masked.

- kind: spend for a card approval or payment, cancel for 승인취소 or 결제취소, withdraw for 출금, deposit for 입금.
- amount_krw is the amount in whole won, or null if only a foreign amount is shown (then set foreign_amount like "USD 12.99").
- merchant is the store, or the counterparty for bank transfers. It is never the card company itself, except when the money goes to the card company (card bill payment).
- issuer is the card company, bank or pay service that sent the message, e.g. "삼성카드", "카카오뱅크", "토스".
- pay_kind is credit, check, bank or pay_money.
- month, day, hour and minute as shown in the message, otherwise null.
- is_top_up is true for charging pay money (충전).
- is_transaction is false for ads, one-time passwords, notices and anything that is not a completed payment or transfer.`;

const CATEGORIZE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["results"],
  properties: {
    results: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["merchant", "category_id"],
        properties: { merchant: { type: "string" }, category_id: nullable("string") },
      },
    },
  },
};

const CATEGORIZE_SYSTEM = `Assign each merchant name from a Korean card statement to the best category id from the list, for a household budget app. Use null when unsure.`;

type Body = Record<string, unknown>;

function categoriesText(body: Body): string {
  const cats = body.categories;
  if (!Array.isArray(cats) || cats.length > 300) throw new BadInput("categories");
  return cats.map((c: any) => `${String(c.id)}: ${String(c.name)}`).join("\n");
}

async function ask(client: Anthropic, path: string, system: string, content: Anthropic.Beta.Messages.BetaContentBlockParam[], schema: object) {
  const res = await client.beta.messages.create({
    model: MODEL,
    max_tokens: 16000,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    output_config: { effort: "low", format: { type: "json_schema", schema: schema as Record<string, unknown> } },
    system,
    messages: [{ role: "user", content }],
  });
  console.log(JSON.stringify({ path, stop: res.stop_reason, usage: res.usage }));
  if (res.stop_reason === "refusal") throw new Refused();
  const text = res.content.find((b) => b.type === "text");
  if (!text || text.type !== "text") throw new Error("empty response");
  return JSON.parse(text.text);
}

async function scan(client: Anthropic, body: Body) {
  const images = body.images;
  if (!Array.isArray(images) || images.length === 0 || images.length > 8) throw new BadInput("images");
  const content: Anthropic.Beta.Messages.BetaContentBlockParam[] = images.map((img: any) => {
    if (!["image/jpeg", "image/png", "image/webp"].includes(img?.media_type) || typeof img?.data !== "string") throw new BadInput("image");
    return { type: "image", source: { type: "base64", media_type: img.media_type, data: img.data } };
  });
  const hints = Array.isArray(body.hints) ? body.hints.slice(0, 30).map(String) : [];
  content.push({
    type: "text",
    text: `today: ${String(body.today ?? new Date().toISOString().slice(0, 10))}\n\ncategories (id: name):\n${categoriesText(body)}` +
      (hints.length ? `\n\nuser hints (keyword → category):\n${hints.join("\n")}` : ""),
  });
  return { result: await ask(client, "/scan", SCAN_SYSTEM, content, SCAN_SCHEMA) };
}

async function parse(client: Anthropic, body: Body) {
  const text = body.text;
  if (typeof text !== "string" || text.length === 0 || text.length > 2000) throw new BadInput("text");
  return ask(client, "/parse", PARSE_SYSTEM, [{ type: "text", text }], PARSE_SCHEMA);
}

async function categorize(client: Anthropic, body: Body) {
  const merchants = body.merchants;
  if (!Array.isArray(merchants) || merchants.length === 0 || merchants.length > 50) throw new BadInput("merchants");
  const text = `categories (id: name):\n${categoriesText(body)}\n\nmerchants:\n${merchants.map(String).join("\n")}`;
  return ask(client, "/categorize", CATEGORIZE_SYSTEM, [{ type: "text", text }], CATEGORIZE_SCHEMA);
}

const json = (data: unknown, status = 200) => Response.json(data, { status });

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method !== "POST") return json({ error: "POST only" }, 405);
    if (!env.APP_TOKEN || req.headers.get("x-app-token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);
    let body: Body;
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid json" }, 400);
    }
    const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
    try {
      switch (new URL(req.url).pathname) {
        case "/scan":
          return json(await scan(client, body));
        case "/parse":
          return json(await parse(client, body));
        case "/categorize":
          return json(await categorize(client, body));
        default:
          return json({ error: "not found" }, 404);
      }
    } catch (e) {
      if (e instanceof BadInput) return json({ error: `invalid ${e.message}` }, 400);
      if (e instanceof Refused) return json({ error: "refused" }, 422);
      if (e instanceof Anthropic.RateLimitError) return json({ error: "rate limited" }, 429);
      if (e instanceof Anthropic.APIError) return json({ error: `upstream ${e.status}` }, 502);
      throw e;
    }
  },
};
