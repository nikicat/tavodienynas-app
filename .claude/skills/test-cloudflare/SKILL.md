---
name: test-cloudflare
description: Test Cloudflare challenge handling by routing emulator traffic through Tor
disable-model-invocation: true
---

# Test Cloudflare Challenge Handling

Route Android emulator traffic through Tor to force Cloudflare to challenge manodienynas.lt. This reproduces the issue Google Play reviewers hit when their IP gets flagged.

## Steps

### 1. Start Tor and privoxy

Privoxy must have `forward-socks5t / 127.0.0.1:9050 .` in `/etc/privoxy/config`.

```bash
sudo systemctl start tor privoxy
```

### 2. Verify challenge triggers

```bash
curl -sI --proxy http://127.0.0.1:8118 "https://www.manodienynas.lt/" | grep cf-mitigated
```

Should show `cf-mitigated: challenge`. If not, Tor circuit may need rotation — restart tor.

### 3. Build and start emulator with proxy

```bash
./gradlew assembleRelease
```

```bash
export ANDROID_SDK_ROOT=/home/nb/.android
export QT_QPA_PLATFORM=xcb
$ANDROID_SDK_ROOT/emulator/emulator -avd Test_API_36 -http-proxy http://127.0.0.1:8118
```

Use `Test_API_36` AVD. `Small_Phone` has a broken sysimage path.

### 4. Install and launch

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n lt.tavodienynas.app/.MainActivity
```

### 5. Verify

Take a screenshot after ~15 seconds:

```bash
adb exec-out screencap -p > /tmp/cf-test.png
```

**Pass**: Cloudflare Turnstile widget loads ("Verify you are human" checkbox). Through Tor it requires a manual click; for non-Tor traffic (Google reviewers) it auto-resolves.

**Fail**: "Incompatible browser extension or network configuration" error — challenge resources are being blocked.

### 6. Cleanup

```bash
sudo systemctl stop privoxy tor
```
