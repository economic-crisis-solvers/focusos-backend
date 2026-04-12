#!/usr/bin/env python3
"""
seed_demo_data.py — Fixed version
Sends backdated timestamps so events spread across 7 days properly.

Usage:
    python seed_demo_data.py --url https://focusos.railway.app --email demo@focusos.dev --password demo1234
"""

import asyncio, argparse, httpx, random, datetime, math

FOCUS_PATTERNS = {
    8:  (70, 12),  9:  (78, 10),  10: (82, 8),
    11: (75, 12),  12: (45, 15),  13: (40, 18),
    14: (35, 20),  15: (38, 20),  16: (60, 15),
    17: (65, 12),  18: (55, 15),  19: (50, 20),
    20: (45, 20),  21: (40, 25),
}

def make_signals(score):
    if score >= 70:
        return {
            "tabSwitchesPerMin": round(random.uniform(0.5, 2.5), 2),
            "typingMeanIntervalMs": round(random.uniform(120, 220), 2),
            "typingStdDevMs": round(random.uniform(20, 60), 2),
            "scrollVelocityPxSec": round(random.uniform(50, 200), 2),
            "scrollDirectionChanges": round(random.uniform(1, 4), 2),
            "idleFlag": 0,
            "urlCategory": random.choice(["work", "work", "work", "other"]),
            "activeMinutesThisSession": round(random.uniform(15, 45), 2)
        }
    elif score >= 45:
        return {
            "tabSwitchesPerMin": round(random.uniform(3, 6), 2),
            "typingMeanIntervalMs": round(random.uniform(180, 350), 2),
            "typingStdDevMs": round(random.uniform(60, 120), 2),
            "scrollVelocityPxSec": round(random.uniform(150, 500), 2),
            "scrollDirectionChanges": round(random.uniform(4, 10), 2),
            "idleFlag": 0,
            "urlCategory": random.choice(["work", "other", "news"]),
            "activeMinutesThisSession": round(random.uniform(5, 20), 2)
        }
    else:
        return {
            "tabSwitchesPerMin": round(random.uniform(8, 15), 2),
            "typingMeanIntervalMs": 0,
            "typingStdDevMs": 0,
            "scrollVelocityPxSec": round(random.uniform(600, 1200), 2),
            "scrollDirectionChanges": round(random.uniform(12, 25), 2),
            "idleFlag": random.choice([0, 0, 1]),
            "urlCategory": random.choice(["social", "entertainment", "news"]),
            "activeMinutesThisSession": round(random.uniform(1, 8), 2)
        }

async def seed(base_url, email, password):
    async with httpx.AsyncClient(base_url=base_url, timeout=30.0) as client:
        # Login or register
        r = await client.post("/auth/register", json={"email": email, "name": "Demo User", "password": password})
        if r.status_code == 409:
            r = await client.post("/auth/login", json={"email": email, "password": password})
        r.raise_for_status()
        token = r.json()["accessToken"]
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        print(f"✅ Authenticated as {email}")

        total = 0
        now = datetime.datetime.now(datetime.timezone.utc)

        for days_ago in range(7, 0, -1):
            day = now - datetime.timedelta(days=days_ago)
            count = 0

            for hour in range(8, 22):
                base, var = FOCUS_PATTERNS.get(hour, (55, 15))

                # Send 2 events per hour, at :00 and :30
                for minute in [0, 30]:
                    score = max(0, min(100, int(random.gauss(base, var))))
                    signals = make_signals(score)

                    # Build exact backdated timestamp in ISO 8601 format
                    ts = day.replace(hour=hour, minute=minute, second=0, microsecond=0)
                    timestamp_str = ts.strftime("%Y-%m-%dT%H:%M:%SZ")

                    try:
                        r = await client.post("/api/events", headers=headers, json={
                            "sessionId": "00000000-0000-0000-0000-000000000001",
                            "timestamp": timestamp_str,
                            "signals": signals,
                            "windowCount": 6,
                        })
                        if r.status_code == 200:
                            count += 1
                        elif r.status_code == 429:
                            print(f"  ⚠️  Rate limited at {hour}:{minute:02d} — waiting 65s...")
                            await asyncio.sleep(65)
                        else:
                            print(f"  ❌ {r.status_code}: {r.text[:60]}")
                    except Exception as e:
                        print(f"  ❌ Error: {e}")

                    await asyncio.sleep(0.05)

            day_name = day.strftime("%A %b %d")
            print(f"  📅 {day_name}: {count} events")
            total += count

        print(f"\n✅ Done. {total} total events across 7 days.")
        print("   Dashboard heatmap should now show proper spread across days and hours.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url",      default="http://localhost:8080")
    parser.add_argument("--email",    default="demo@focusos.dev")
    parser.add_argument("--password", default="demo1234")
    args = parser.parse_args()
    asyncio.run(seed(args.url, args.email, args.password))
