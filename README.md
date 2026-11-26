# PortraitCam

Android camera app for Samsung Galaxy A53 5G testing.

## Features
- CameraX capture and digital zoom
- Multi-frame super-resolution pipeline
- AI upscaling (Real-ESRGAN TFLite models)
- Portrait background blur with depth estimation
- HDR, noise reduction and image enhancement

## Build
GitHub Actions builds a debug APK on every push. Download the `PortraitCam-apk` artifact from the latest successful **Build APK** workflow run, or install the APK attached to the `latest` GitHub release.

## Device note
Zoom enhancement is computational photography; it cannot recreate optical telephoto detail that the phone sensor did not capture. Best results require good light and a steady subject.
