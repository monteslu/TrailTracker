# 📱 TrailTracker Android App Specification

**Project Name:** `TrailTracker`  
**Purpose:** High-framerate image and GPS recorder for long-duration bike rides. Outputs timestamped image frames and GPS logs for external use or analysis.

All timestamps are in Unix milliseconds. All angles in degrees, all distances in meters.

_Last Updated: 2025-08-05 19:55:31_

---

## 🧱 Technical Stack

- **Platform:** Android
- **Language:** Kotlin or Java (vibe-code friendly)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (latest stable)
- **Dependencies:**
  - CameraX or Camera2 API
  - FusedLocationProviderClient (Google Play Services)
  - AndroidX Lifecycle + ViewModel (recommended)

---

## ✅ Core Functional Requirements

### 1. 📸 Image Frame Capture
- Resolution: **1920×1080 (Full HD)**
- Framerate: **30 FPS**
- Format: **WebP**, quality **92%**
- Naming: Unix timestamp in milliseconds, e.g. `1754419666486.webp`
- Storage path:
  ```
  /storage/emulated/0/TrailTracker/<ROUTE_NAME>/
  ```

### 2. 🛰 GPS and Compass Logging

- **GPS Source:** `FusedLocationProviderClient`
  - Request interval: **100ms**
  - Expected fix rate: **5–10 Hz**
  - Accepts all accurate location fixes
- **Compass Source:** Android **rotation vector sensor**
  - Derived from **magnetometer + accelerometer**
  - Heading is in **degrees from magnetic north**
  - Captured alongside each GPS fix
- **Output Format:** `points.jsonl` (newline-delimited JSON objects)
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
- Stored in:
  ```
  /storage/emulated/0/TrailTracker/<ROUTE_NAME>/points.jsonl
  ```

### 3. 🗂 Route Session Handling
- New session = prompts for unique route name
- Creates:
  ```
  /TrailTracker/<ROUTE_NAME>/
  ```


---

## 🖼 UI Requirements

### Main UI Layout
- Full-screen camera preview (landscape preferred)
- GPS overlay text (lat/lon/alt/speed/accuracy)
- Large touch targets:
  - `[▶ Start New Session]`
  - `[📂 Resume Saved Session]`

### menu:
- View past sessions
- Delete session
- Settings

---

## 🔁 Session Management

### `Start New Session` button:
- Prompts for route name
- Checks if folder exists
- If not:
  * creates new route folder
  * shows camera and gps data
  * waits for Start to record images and gps/compass data
- If one is already exists:
  * The app loads previous session state
  * shows camera and gps data
  * Recording does not begin until Start is pressed
  * Frame count and timestamp preview are displayed as paused


### `Resume Saved Session` button:
- Lists folders in `/TrailTracker/`
- Continues writing images and appending GPS data

---

## 🔋 Power + Performance

- Keeps screen awake during session (WakeLock)
- Optimized for use while powered externally
- Prevents multiple sessions at once (no need for per-route lock file)

---

## 📂 Storage Notes

- All data is stored on local internal/shared storage
- User retrieves data via USB or ADB (`adb pull /sdcard/TrailTracker`)
- Frame count: ~216,000 images per 2-hour ride at 30fps

---

## ⚠️ Edge Cases & Recovery

| Condition | Behavior |
|----------|----------|
| Out of storage | Show warning below 10GB, auto-pause below 2GB |
| No camera/GPS permission | Show blocking dialog |
| USB plugged | App behaves normally (does not trigger transfer) |

---

## 🧠 File Structure

```
/TrailTracker/
  <RouteName>/
    1754419666486.webp
    ...
    points.jsonl
```
---

## 🚀 Example Flow

1. Open app  
2. Tap `Start New Session`, name it "TempeLoop"  
3. App records 30fps images + GPS into `TrailTracker/TempeLoop`  
4. Optionally pause or resume  
5. Data is retrieved later via USB or ADB  
6. No rendering or interpretation is performed in-app  
