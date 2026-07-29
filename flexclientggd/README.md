# Goose Goose Duck — Modded APK Builder

Bu repo, Goose Goose Duck APK'sını otomatik olarak modifiye edip imzalayarak indirilebilir hale getiren bir CI/CD sistemi içerir.

## 🚀 Otomatik Build

`main` branch'e her push'ta GitHub Actions otomatik olarak:
1. APK'yı decode eder (apktool)
2. Patch'leri uygular
3. Yeniden derler ve imzalar
4. Artifact olarak yükler

### APK'yı İndirmek

1. GitHub'da **Actions** sekmesine git
2. En son başarılı "Build Modified APK" workflow'unu aç
3. **Artifacts** kısmından `GooseGooseDuck-Modified-XXX` dosyasını indir

### Release Oluşturmak

1. Actions → "Build Modified APK" → "Run workflow"
2. `release_tag` alanına versiyon gir (örn. `v4.09.00-mod`)
3. Çalıştır — otomatik GitHub Release oluşturulur

---

## 🔧 Değişiklikler

| Patch | Durum | Açıklama |
|-------|-------|----------|
| Ödeme Kaldır | ✅ | Google Play Billing + Samsung IAP devre dışı |
| Tüm Kıyafetler | ⚠️ | Smali seviyesinde patch uygulandı. Oyun Unity IL2CPP kullandığı için tam unlock `libil2cpp.so` binary patch'i gerektirir. |

---

## 📁 Repo Yapısı

```
original/           → Orijinal APK (Git LFS)
patches/            → Modifikasyon scriptleri
  remove_billing.sh      → Billing/IAP kaldır
  unlock_cosmetics.sh    → Kıyafet unlock
.github/workflows/  → GitHub Actions CI
```

---

## ⚠️ Yasal Uyarı

Bu repo yalnızca eğitim ve araştırma amaçlıdır.
