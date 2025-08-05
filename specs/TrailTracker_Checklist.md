# ✅ Dev Checklist: TrailTracker

## UI

- [ ] Menu `[☰]` is always visible, even during recording
- [ ] Selecting `Start New Session` while recording pauses current session and prompts for new name

- [ ] Show preview: camera + GPS + compass
- [ ] Display frame count and duration in paused state
- [ ] Button: `[▶ Start New Session]`
- [ ] Button: `[📂 Resume Saved Session]`
- [ ] Settings menu
- [ ] Route name input and confirmation

## Recording

- [ ] Start/Stop triggers frame and GPS recording
- [ ] Save images as WebP 92% at 30fps, 1080p
- [ ] Name each image by timestamp in milliseconds
- [ ] Log GPS/compass to `points.jsonl`

## Storage

- [ ] All files saved to: `/storage/emulated/0/TrailTracker/<ROUTE_NAME>/`
- [ ] Auto-pause at <2GB free
- [ ] Storage warning at 10GB

## GPS/Compass

- [ ] Use FusedLocationProviderClient for GPS
- [ ] Use rotation vector for compass heading
- [ ] Log at 5–10Hz, 100ms interval requested

## Power Handling

- [ ] Acquire WakeLock during session
- [ ] Disable screen timeout
- [ ] No USB sync behavior
