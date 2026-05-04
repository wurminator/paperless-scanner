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

## Headless Emulator (Server)

```bash
# Start emulator (headless, needs KVM group)
sg kvm -c "export JAVA_HOME=~/android-build/jdk-17.0.19+10 && \
  export ANDROID_HOME=~/android-sdk && \
  export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/emulator:\$ANDROID_HOME/platform-tools:\$PATH && \
  emulator -avd test_device -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -memory 2048 -no-snapshot -wipe-data &"

# Wait for boot
adb wait-for-device
adb shell getprop sys.boot_completed  # should print "1"

# Install APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Running Tests

```bash
# Build the APK first
./gradlew assembleDebug

# Install on emulator
adb install app/build/outputs/apk/debug/app-debug.apk

# Run all flows
maestro test .maestro/flows/

# Run a single flow
maestro test .maestro/flows/01-smoke-launch.yaml
```

## Test Structure

```
.maestro/
├── config.yaml              # App ID, APK path, tags
├── test-suite.yaml          # Suite definition
├── helpers/
│   └── create-test-pdf.yaml # Shared helper: creates test PDF
└── flows/
    ├── 01-smoke-launch.yaml              # App launches without crash
    ├── 02-quick-upload-pdf.yaml          # App health check (PDF)
    ├── 03-quick-upload-image.yaml        # App health check (image)
    ├── 04-quick-upload-rejected-type.yaml # App health check (blocked type)
    └── 05-onboarding-fresh-install.yaml  # Fresh install → analytics consent → setup
```

## Writing New Flows

Flows are YAML files in `.maestro/flows/`. Key commands:

```yaml
appId: com.paperless.scanner.debug
tags:
  - my-tag
---
- launchApp                    # Start the app
- tapOn: "Upload"              # Tap text matching "Upload"
- assertVisible: "Documents"   # Verify text is on screen
- back                         # Press system back
```

### Waiting for elements

```yaml
- extendedWaitUntil:
    visible:
      text: "Welcome"
    timeout: 10000
```

### Device State

```yaml
- clearState    # Wipe app data (fresh install simulation)
- takeScreenshot: /tmp/maestro-screenshot.png
```

## Troubleshooting

- **"No device found"**: Start emulator, verify with `adb devices`
- **"Unable to launch app"**: Debug build uses `com.paperless.scanner.debug` as appId
- **KVM permissions**: Must be in `kvm` group — `sudo usermod -aG kvm $USER`, then relogin
- **Emulator CPU error**: Use `sg kvm -c "emulator ..."` if session doesn't have kvm group yet

## CI Integration (Future)

```bash
maestro cloud --apiKey <KEY> .maestro/test-suite.yaml
```
