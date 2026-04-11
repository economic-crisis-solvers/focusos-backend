# FocusOS API — Java / Spring Boot (Dev C)

FastAPI → Spring Boot rewrite. Same endpoints, same Supabase schema, same Realtime contract.
Java 21 + Spring Boot 3.3 + PostgreSQL + ONNX Runtime.

---

## Project Structure

```
src/main/java/com/focusos/
├── FocusOsApplication.java          ← entry point
├── controller/
│   ├── AuthController.java          ← POST /auth/register, /auth/login
│   ├── EventController.java         ← POST /api/events  (core pipeline)
│   ├── ScoreController.java         ← GET /api/score/live, /api/score/history
│   ├── InsightsController.java      ← GET /api/insights
│   ├── SettingsController.java      ← GET/PUT /api/settings
│   ├── OverrideController.java      ← POST /api/override
│   └── HealthController.java        ← GET /health
├── service/
│   ├── MlService.java               ← ONNX inference + rule-based fallback
│   ├── RealtimeService.java         ← Supabase Realtime broadcast
│   └── ResidueService.java          ← Attention Residue calculation
├── model/
│   ├── entity/                      ← JPA entities (4 tables)
│   └── dto/                         ← Request/Response objects
├── repository/                      ← Spring Data JPA repos + native queries
├── security/
│   ├── JwtUtil.java                 ← Token generation + validation
│   └── JwtAuthFilter.java           ← Injects user ID from Bearer token
└── config/
    ├── SecurityConfig.java          ← Spring Security + CORS
    └── GlobalExceptionHandler.java  ← Clean error responses
```

---

## Quick Start

### Prerequisites
- Java 21
- Maven 3.9+
- A Supabase project with the migration run (use `supabase_migration.sql` from the repo)

### 1. Clone & configure

```bash
git clone <repo>
cd focusos-java
cp .env.example .env
# Fill in .env with your Supabase credentials
```

### 2. Export env vars and run

```bash
export $(cat .env | xargs)
mvn spring-boot:run
```

Or build a JAR (what Railway runs):
```bash
mvn clean package -DskipTests
java -jar target/focusos-api-1.0.0.jar
```

Visit: http://localhost:8080 — API is live.
Swagger UI is not included by default; use the Postman collection.

---

## Supabase Setup (Day 1)

Same SQL migration as before — run `supabase_migration.sql` in Supabase SQL Editor.
Share with the team after:
```
SUPABASE_URL      = https://xxxx.supabase.co
SUPABASE_ANON_KEY = eyJ...   ← Dev A (dashboard) and Dev B (mobile) need this
```
Never share `SUPABASE_SERVICE_KEY` — backend only.

---

## Railway Deployment (Day 14)

1. Push to GitHub
2. Railway → New Project → Deploy from GitHub → select `focusos-java/`
3. Set environment variables in Railway dashboard:
   ```
   DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
   SUPABASE_URL, SUPABASE_SERVICE_KEY
   JWT_SECRET
   MODEL_PATH=model.onnx
   ```
4. Railway auto-detects Maven and runs: `mvn clean package && java -jar target/*.jar`
5. Add `RAILWAY_URL` secret to GitHub repo → keep-warm Action runs every 5 min

> **Getting DB credentials from Supabase:**
> Project Settings → Database → Connection string → URI format.
> Extract: host, port, dbname, user, password.

---

## When Dev D gives you model.onnx

1. Drop `model.onnx` into `focusos-java/` root
2. Set `MODEL_PATH=model.onnx` in your env
3. Restart — `MlService` auto-loads it on startup
4. Rule-based fallback deactivates automatically

**Feature vector must be exactly 10 floats in this order:**
```
[tab_switches_per_min, typing_mean_interval_ms, typing_std_dev_ms,
 scroll_velocity_px_sec, scroll_direction_changes, idle_flag,
 url_cat_work, url_cat_social, url_cat_entertainment, url_cat_other]
```
Confirm this matches Dev D's training feature order before Day 3.

---

## Realtime Contract (for Dev A & Dev B)

Subscribe to channel: `focus-{userId}` using Supabase anon key.

| Event | Trigger | Payload |
|-------|---------|---------|
| `focus_score_update` | Every event batch | `{ score, state, timestamp }` |
| `focus_active_change` | Threshold crossing or override | `{ focus_active, score, timestamp, override? }` |

```javascript
// JS example (same for dashboard and mobile)
const channel = supabase.channel(`focus-${userId}`)
  .on('broadcast', { event: 'focus_score_update' }, ({ payload }) => {
    setScore(payload.score)
  })
  .on('broadcast', { event: 'focus_active_change' }, ({ payload }) => {
    setFocusActive(payload.focus_active)
  })
  .subscribe()
```

---

## API Reference

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/auth/register` | ❌ | Register → returns JWT |
| POST | `/auth/login` | ❌ | Login → returns JWT |
| POST | `/api/events` | ✅ | Ingest signals, ML inference, broadcast |
| GET | `/api/score/live` | ✅ | Latest focus score |
| GET | `/api/score/history?days=7&granularity=hour` | ✅ | Historical scores |
| GET | `/api/insights?days=7` | ✅ | Distractions + peak hours + residue |
| GET | `/api/settings` | ✅ | User settings |
| PUT | `/api/settings` | ✅ | Update settings |
| POST | `/api/override` | ✅ | Manual shield override |
| GET | `/health` | ❌ | Keep-warm ping |

**Auth:** `Authorization: Bearer <token>` on all protected endpoints.

**Note:** Java JSON uses camelCase keys. Auth response field is `accessToken` (not `access_token`).

---

## Seed Demo Data

```bash
pip install httpx
python seed_demo_data.py --url http://localhost:8080
# or against production:
python seed_demo_data.py --url https://focusos.railway.app
```

Populates 7 days of realistic data. Monday 2–4 PM will show as worst focus window.

---

## Demo Day Checklist

- [ ] `GET /health` returns `{"status":"ok"}` on Railway URL
- [ ] GitHub Actions keep-warm is active (Actions tab → Keep Railway Warm)
- [ ] `model.onnx` dropped in and server restarted — logs show "ONNX model loaded"
- [ ] Demo account seeded with 7-day history
- [ ] Supabase row count checked (free tier limit: 500MB)
- [ ] Realtime latency tested: Chrome event → mobile receive < 5 seconds
- [ ] Backup: screen-recorded video of live sync moment saved offline
