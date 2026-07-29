# Patches

Bu klasör APK modifikasyon scriptlerini içerir. GitHub Actions tarafından otomatik çalıştırılır.

## `remove_billing.sh`
- Google Play Billing Client'ı devre dışı bırakır
- `isReady()` her zaman `false` döndürür
- `launchBillingFlow()` anında `BILLING_UNAVAILABLE` döndürür
- Samsung IAP stublanır
- `AndroidManifest.xml`'den billing permission kaldırılır

## `unlock_cosmetics.sh`
- `isUnlocked`, `isPurchased`, `isOwned` gibi metodları smali seviyesinde `true` döndürecek şekilde patch'ler
- **NOT:** Bu oyun Unity IL2CPP kullanmaktadır. Kıyafet mantığının büyük kısmı `libil2cpp.so` içindedir. Tam unlock için binary-level patch gerekebilir.
