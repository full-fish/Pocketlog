# Pocketlog AI 프록시 (Cloudflare Worker)

앱은 OpenAI API를 직접 부르지 않고 이 Worker를 거칩니다. API 키가 APK 안에 들어가면 디컴파일로 털리기 때문이에요.

| 경로 | 하는 일 |
|---|---|
| `POST /scan` | 쇼핑·결제 스크린샷 → 주문·품목 JSON |
| `POST /parse` | 카드 문자 규칙으로 못 읽은 알림 → 거래 JSON (마스킹된 문구만 받음) |
| `POST /categorize` | 처음 보는 가맹점 → 카테고리 id |

모든 요청은 `x-app-token` 헤더가 `APP_TOKEN` 시크릿과 같아야 합니다.

## 시크릿

루트의 `.env` (git에 안 올라감)에 둡니다.

```
OPENAI_API_KEY=sk-...
APP_TOKEN=<아무 긴 랜덤 문자열, openssl rand -hex 24>
```

## 배포

```bash
cd server
npm install
npx wrangler login
npx wrangler deploy
npx wrangler secret bulk ../.env
```

배포 후 루트의 `local.properties`에 적고 앱을 다시 빌드하세요.

```
ai.proxyUrl=https://pocketlog-ai.<내 계정>.workers.dev
ai.appToken=<APP_TOKEN과 같은 값>
```

## 로컬 실행

`npx wrangler dev --env-file ../.env`. 타입 검사는 `npm run check`.

## 비용·로그

- 모델: 앱 설정에서 고른 것 (`gpt-6-astra`, `gpt-6.1-sol`, `gpt-5.5`, `gpt-6-luna` 중 하나, 그 밖은 기본값 `gpt-5.5`), `reasoning_effort: low`, 구조화 출력(JSON 스키마, strict). 스샷 1장 약 1~60원 (측정: TODO.md #3).
- 요청마다 토큰 사용량을 `console.log`로 남깁니다. `npx wrangler tail`로 확인하세요.
- Workers 무료 플랜은 요청당 CPU 10ms 제한이 있어 큰 스크린샷 여러 장을 보내면 실패할 수 있습니다. 그러면 유료 플랜($5/월)을 쓰세요.
- 사용자별 사용량 제한은 아직 없습니다 (스토어 출시 전 P1에서 KV/D1로 추가).
