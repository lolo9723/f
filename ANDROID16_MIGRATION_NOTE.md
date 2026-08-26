# Android 16 / API 36 migration gate

Bu proje kalıcı Android dağıtımı hedeflediği için Android 16 (API 36) test kapısı zorunludur.

- compileSdk: 36
- targetSdk: 36
- minSdk: 26
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- CI cihaz kapıları: API 35 + API 36
- Android Test Orchestrator: 1.6.1

Kabul kuralı: API 35 veya API 36 instrumentation işlerinden herhangi biri FAIL ise APK final kabul edilmez.
