#!/bin/bash
# ============================================================
# Smali-level billing removal (dex-only patching)
# Works on the smali_classes*/ directories produced by baksmali
# ============================================================
set -e

echo "🔧 Billing patch başlıyor (smali klasörleri)..."

PATCHED=0

# Iterate all smali directories
for SMALI_DIR in smali_classes*/; do
    [ -d "$SMALI_DIR" ] || continue
    echo "📂 İşleniyor: $SMALI_DIR"

    # ─── 1. BillingClient stub ─────────────────────────────────────────
    # Find BillingClient.smali files and stub isReady() / launchBillingFlow()
    find "$SMALI_DIR" -type f -name "*.smali" -path "*/billingclient/*" | while read f; do
        echo "  Billing stub: $f"
        python3 - "$f" << 'PYEOF'
import sys, re

file = sys.argv[1]
with open(file) as fp:
    content = fp.read()

original = content

# Stub isReady()Z → always return false (0)
content = re.sub(
    r'(\.method public isReady\(\)Z\n)(.*?)(\.end method)',
    r'.method public isReady()Z\n'
    r'    .locals 1\n'
    r'    const/4 v0, 0x0\n'
    r'    return v0\n'
    r'.end method',
    content, flags=re.DOTALL
)

# Stub isFeatureSupported → return FEATURE_NOT_SUPPORTED (1)
content = re.sub(
    r'(\.method public isFeatureSupported\([^)]*\)[^\n]*\n)(.*?)(\.end method)',
    r'\1'
    r'    .locals 2\n'
    r'    new-instance v0, Lcom/android/billingclient/api/BillingResult;\n'
    r'    invoke-direct {v0}, Lcom/android/billingclient/api/BillingResult;-><init>()V\n'
    r'    return-object v0\n'
    r'.end method',
    content, flags=re.DOTALL
)

if content != original:
    with open(file, 'w') as fp:
        fp.write(content)
    print(f"    ✅ Patched: {file}")
PYEOF
        PATCHED=$((PATCHED+1))
    done

    # ─── 2. Stub entire billing client builder ──────────────────────────
    # BillingClient$Builder: build() always returns null-like client
    find "$SMALI_DIR" -type f -name "Builder.smali" -path "*/billingclient/*" | while read f; do
        echo "  Builder stub: $f"
        python3 - "$f" << 'PYEOF'
import sys, re

file = sys.argv[1]
with open(file) as fp:
    content = fp.read()

original = content

# setListener method → return this (noop)
content = re.sub(
    r'(\.method public setListener\([^)]*\)[^\n]*\n)(.*?)(\.end method)',
    lambda m: m.group(1) + '    .locals 1\n    return-object p0\n' + m.group(3),
    content, flags=re.DOTALL
)

if content != original:
    with open(file, 'w') as fp:
        fp.write(content)
    print(f"    ✅ Patched Builder: {file}")
PYEOF
    done

    # ─── 3. Zero out all IAP/purchase listener callbacks ────────────────
    find "$SMALI_DIR" -type f -name "*.smali" | xargs grep -l "onPurchasesUpdated\|onBillingSetupFinished" 2>/dev/null | while read f; do
        echo "  Purchase callback stub: $f"
        python3 - "$f" << 'PYEOF'
import sys, re

file = sys.argv[1]
with open(file) as fp:
    content = fp.read()

original = content

# onPurchasesUpdated: do nothing
content = re.sub(
    r'(\.method public onPurchasesUpdated\([^)]*\)V\n)(.*?)(\.end method)',
    lambda m: m.group(1) + '    .locals 0\n    return-void\n' + m.group(3),
    content, flags=re.DOTALL
)

if content != original:
    with open(file, 'w') as fp:
        fp.write(content)
    print(f"    ✅ Patched callback: {file}")
PYEOF
    done

done

echo ""
echo "✅ Smali billing patch tamamlandı"
