#!/usr/bin/env python3
"""
seed_demo_data.py
-----------------
Seeds 7 days of realistic focus history into the FocusOS API.
Works against any backend (Java or Python) — just calls the REST API.

Usage:
    pip install httpx
    python seed_demo_data.py --url https://focusos.railway.app
"""

import asyncio, argparse, httpx, random, datetime

FOCUS_PATTERNS = {
    # hour: (base_score, variance)
    8:  (70, 12),  9:  (78, 10),  10: (82, 8),
    11: (75, 12),  12: (45, 15),  13: (40, 18),
    14: (35, 20),  15: (38, 20),  16: (60, 15),  # Mon 2-4 PM = worst (demo talking point)
    17: (65, 12),  18: (55, 15),  19: (50, 20),
    20: (45, 20),  21: (40, 25),
}

def make_signals(score):
    if score >= 70:
        return {"tab_switches_per_min": random.uniform(0.5, 2.5),
                "typing_mean_interval_ms": random.uniform(120, 220),
                "typing_std_dev_ms": random.uniform(20, 60),
                "scroll_velocity_px_sec": random.uniform(50, 200),
                "scroll_direction_changes": random.randint(1, 4),
                "idle_flag": 0, "url_category": "work",
                "active_minutes_this_session": random.uniform(15, 45)}
    elif score >= 45:
        return {"tab_switches_per_min": random.uniform(3, 6),
                "typing_mean_interval_ms": random.uniform(180, 350),
                "typing_std_dev_ms": random.uniform(60, 120),
                "scroll_velocity_px_sec": random.uniform(150, 500),
                "scroll_direction_changes": random.randint(4, 10),
                "idle_flag": 0, "url_category": random.choice(["work","other","news"]),
                "active_minutes_this_session": random.uniform(5, 20)}
    else:
        return {"tab_switches_per_min": random.uniform(8, 15),
                "typing_mean_interval_ms": 0, "typing_std_dev_ms": 0,
                "scroll_velocity_px_sec": random.uniform(600, 1200),
                "scroll_direction_changes": random.randint(12, 25),
                "idle_flag": random.choice([0, 0, 1]),
                "url_category": random.choice(["social","entertainment","news"]),
                "active_minutes_this_session": random.uniform(1, 8)}

async def seed(base_url, email, password):
    async with httpx.AsyncClient(base_url=base_url, timeout=15) as client:
        r = await client.post("/auth/register", json={"email": email, "name": "Demo User", "password": password})
        if r.status_code == 409:
            r = await client.post("/auth/login", json={"email": email, "password": password})
        r.raise_for_status()
        headers = {"Authorization": f"Bearer {r.json()['accessToken']}"}
        print(f"✅ Authenticated as {email}")

        total = 0
        now   = datetime.datetime.utcnow()

        for days_ago in range(7, 0, -1):
            day = now - datetime.timedelta(days=days_ago)
            count = 0
            for hour in range(8, 22):
                base, var = FOCUS_PATTERNS.get(hour, (55, 15))
                for _ in range(2):
                    score   = max(0, min(100, int(random.gauss(base, var))))
                    signals = make_signals(score)
                    r = await client.post("/api/events", headers=headers, json={
                        "sessionId": "00000000-0000-0000-0000-000000000001",
                        "signals": signals, "windowCount": 6
                    })
                    if r.status_code == 200:
                        count += 1
                    elif r.status_code == 429:
                        print("  ⚠️  Rate limited — waiting 65s...")
                        await asyncio.sleep(65)
                    await asyncio.sleep(0.1)
            print(f"  📅 {day.strftime('%A %b %d')}: {count} events")
            total += count
            await asyncio.sleep(1)

        print(f"\n✅ Done. {total} events seeded across 7 days.")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--url",      default="http://localhost:8080")
    p.add_argument("--email",    default="demo@focusos.dev")
    p.add_argument("--password", default="demo1234")
    args = p.parse_args()
    asyncio.run(seed(args.url, args.email, args.password))
