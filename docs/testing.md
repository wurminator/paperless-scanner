# Maestro UI Testing

This project uses [Maestro](https://maestro.mobile.dev/) for black-box UI testing.

## Setup

```bash
# Install Maestro (one-time)
curl -fsSL "https://get.maestro.mobile.dev" | bash
export PATH="$PATH:$HOME/.maestro/bin"

# Add to shell (permanent)
echo 'export PATH="$PATH:$HOME/.maestro/bin"' >> ~/.bashrc
```

## Prerequisites

- Built APK: `./gradlew assembleDebug`
- Running Android Emulator **or** connected physical device with USB debugging
- ADB in PATH (`~/android-sdk/platform-tools/`)

## Running Tests

```bash
# Build the APK first
./gradlew assembleDebug

# Run a single flow
maestro test .maestro/flows/01-smoke-launch.yaml

# Run all flows (full suite)
maestro test .maestro/test-suite.yaml

# Run with specific APK path
maestro test --apk app/build/outputs/apk/debug/app-debug.apk .maestro/test-suite.yaml

# Run only tagged flows (future: maestro supports --tag filter)
```

## Test Structure

```
.maestro/
├── config.yaml              # App ID, APK path, tags
├── test-suite.yaml          # Full suite — runs all flows
├── helpers/
│   └── create-test-pdf.yaml # Shared helper: creates test PDF
└── flows/
    ├── 01-smoke-launch.yaml              # App launches without crash
    ├── 02-quick-upload-pdf.yaml          # Share PDF → quick upload
    ├── 03-quick-upload-image.yaml        # Share image → quick upload
    ├── 04-quick-upload-rejected-type.yaml # Unsupported type → graceful error
    └── 05-onboarding-fresh-install.yaml  # Fresh install shows setup
```

## Writing New Flows

Flows are YAML files in `.maestro/flows/`. Key commands:

```yaml
appId: com.paperless.scanner
tags:
  - my-tag
---
- launchApp                    # Start the app
- tapOn: "Upload"              # Tap text matching "Upload"
- assertVisible: "Documents"   # Verify text is on screen
- back                         # Press system back
- assertTrue:                  # Custom assertion
    label: "Something happened"
```

### Share Intent Testing

```yaml
- startActivity:
    action: android.intent.action.SEND
    type: "application/pdf"
    extra:
      android.intent.extra.STREAM: "content://..."
```

### Device State

```yaml
- clearState    # Wipe app data (fresh install simulation)
- takeScreenshot: /tmp/maestro-screenshot.png
```

## CI Integration (Future)

For headless CI without emulator, Maestro Cloud is available:
```bash
maestro cloud --apiKey <KEY> .maestro/test-suite.yaml
```

## Troubleshooting

- **"No device found"**: Start emulator or connect device, verify with `adb devices`
- **Flaky tests**: Add `timeout:` to `assertVisible` (default 10s)
- **Share intent tests**: Maestro's intent support is limited — some flows may need `adb shell am broadcast` workarounds
