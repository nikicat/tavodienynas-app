# Project Instructions

## Firebase Test Lab

Always use the Gradle task to run Firebase Test Lab tests, not gcloud directly:

```bash
# Default (MediumPhone.arm, API 34)
./gradlew firebaseTestLab

# Custom device/API
./gradlew firebaseTestLab -Pdevice=SmallPhone.arm -PapiLevel=26
./gradlew firebaseTestLab -Pdevice=MediumTablet.arm -PapiLevel=26
```

Available devices: `MediumPhone.arm`, `SmallPhone.arm`, `MediumTablet.arm`, `Pixel2.arm`
