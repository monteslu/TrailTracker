# TrailTracker: Android Recording App

TrailTracker is a minimal Android app for recording timestamped camera frames and GPS+compass data during cycling routes. The app is designed for clean, structured output with no post-processing or analysis features.

## 🎯 Purpose

- Capture 1080p 30fps images with WebP compression (92%)
- Log GPS location and compass heading in JSONL format
- Save to a uniquely named folder per route
- Designed for later processing using external tools

---

## 📂 Output Structure

```
/storage/emulated/0/TrailTracker/
└── <ROUTE_NAME>/
    ├── 1754419666486.webp
    ├── 1754419666520.webp
    ├── ...
    └── points.jsonl
```

---

## 📸 Image Format

- Resolution: **1920x1080**
- Format: **WebP**
- Frame Rate: **30 fps**
- Quality: **92%**
- Naming: `timestamp.webp` (milliseconds since epoch)

---

## 🛰 GPS + Compass Format

- Format: `points.jsonl` (newline-delimited)
- Each line:
```json
{
  "timestamp": 1754419666486,
  "lat": 33.4212,
  "lon": -111.9383,
  "alt": 354.2,
  "speed": 4.7,
  "accuracy": 3.5,
  "compass": 182.5
}
```

---

## ⚙ Requirements

- AndroidX (Lifecycle)
- CameraX or Camera2
- FusedLocationProviderClient
- SensorManager (Rotation Vector)
- WakeLock

---

## 🧠 Session Behavior

- New route creates new folder
- If folder exists, resumes into it
- **Paused by default until `Start` is pressed**


---

## 🧭 Menu Behavior

- The [☰ Menu] is always accessible — even during active recording
- Selecting `Start New Session` while recording will:
  - Automatically pause current session
  - Prompt user to enter a new route name
  - Begin a new recording session into a new folder
