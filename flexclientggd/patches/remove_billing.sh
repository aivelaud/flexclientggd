#!/bin/bash
# ============================================================
# Patch: Billing / IAP Kaldır
# Tüm ödeme akışlarını devre dışı bırakır.
# ============================================================
set -e
DECODED_DIR="${1:-decoded}"

echo "🔧 Billing patch başlıyor: $DECODED_DIR"

SMALI_ROOT="$DECODED_DIR/smali"
SMALI_DIRS=$(find "$DECODED_DIR" -maxdepth 1 -type d -name "smali*")

# --------------------------------------------------------
# 1. BillingClient — isReady() her zaman false döndür
# --------------------------------------------------------
patch_billing_client() {
    local file="$1"
    echo "  → BillingClient patch: $file"

    # isReady() metodunu bul ve return false yap
    if grep -q "isReady" "$file"; then
        # .method public isReady()Z  →  return false (0)
        python3 - <<PYEOF
import re, sys

with open("$file", "r") as f:
    content = f.read()

# isReady() override: her zaman 0 (false) döndür
pattern = r'(\.method public isReady\(\)Z.*?)(\.end method)'
replacement = r'.method public isReady()Z\n    .locals 1\n    const/4 v0, 0x0\n    return v0\n.end method'
new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open("$file", "w") as f:
    f.write(new_content)
print("    isReady() patched")
PYEOF
    fi
}

# --------------------------------------------------------
# 2. BillingClient — startConnection() callback'ini hemen
#    onBillingSetupFinished(BILLING_UNAVAILABLE) ile çağır
# --------------------------------------------------------
patch_billing_setup() {
    local file="$1"
    echo "  → BillingSetup patch: $file"

    python3 - <<PYEOF
import re

with open("$file", "r") as f:
    content = f.read()

# launchBillingFlow methodunu bul, hemen BILLING_UNAVAILABLE döndür
# BillingResult code 3 = BILLING_UNAVAILABLE
pattern = r'(\.method public launchBillingFlow.*?\.locals\s+\d+)'
def replace_billing_flow(m):
    return m.group(0) + '''
    # PATCH: Billing disabled
    new-instance v0, Lcom/android/billingclient/api/BillingResult;
    invoke-direct {v0}, Lcom/android/billingclient/api/BillingResult;-><init>()V
    return-object v0
'''
new_content = re.sub(pattern, replace_billing_flow, content, flags=re.DOTALL)

with open("$file", "w") as f:
    f.write(new_content)
print("    launchBillingFlow() patched")
PYEOF
}

# --------------------------------------------------------
# 3. AndroidManifest.xml — BILLING permission kaldır
# --------------------------------------------------------
patch_manifest() {
    local manifest="$DECODED_DIR/AndroidManifest.xml"
    if [ -f "$manifest" ]; then
        echo "  → Manifest billing permission kaldırılıyor"
        sed -i 's|<uses-permission android:name="com.android.vending.BILLING"[^/]*/?>||g' "$manifest"
        # Billing service receiver'larını kaldır
        sed -i '/<receiver.*PURCHASE_STATE_CHANGED/,/<\/receiver>/d' "$manifest"
        echo "    Manifest patched"
    fi
}

# --------------------------------------------------------
# 4. billing.properties dosyasını boşalt
# --------------------------------------------------------
patch_billing_properties() {
    local prop="$DECODED_DIR/billing.properties"
    if [ -f "$prop" ]; then
        echo "  → billing.properties sıfırlanıyor"
        echo "# Billing disabled by patch" > "$prop"
    fi
}

# --------------------------------------------------------
# Bütün smali klasörlerini tara ve billing dosyalarını patch'le
# --------------------------------------------------------
for smali_dir in $SMALI_DIRS; do
    echo "📂 Smali dir: $smali_dir"
    
    # BillingClient*.smali dosyaları
    find "$smali_dir" -type f -name "*.smali" | while read smali_file; do
        basename_file=$(basename "$smali_file")
        dirpath=$(dirname "$smali_file")
        
        # com/android/billingclient içindeki dosyalar
        if echo "$smali_file" | grep -q "billingclient/api/BillingClient"; then
            patch_billing_client "$smali_file" 2>/dev/null || true
        fi
        
        # Google Play billing flow
        if echo "$smali_file" | grep -q "billingclient"; then
            # Tüm billing smali dosyalarına basit stub ekle
            if grep -q "launchBillingFlow\|launchPriceChangeConfirmationFlow" "$smali_file" 2>/dev/null; then
                patch_billing_setup "$smali_file" 2>/dev/null || true
            fi
        fi
        
        # Samsung IAP
        if echo "$smali_file" | grep -qi "samsung.*iap\|iap.*samsung\|SamsungIAP\|SamsungPurchase"; then
            echo "  → Samsung IAP stub: $smali_file"
            # Tüm public metodları stub'la
            python3 - "$smali_file" <<PYEOF 2>/dev/null || true
import sys, re
file = sys.argv[1]
with open(file) as f:
    content = f.read()
# public metodların ilk instruction'ından önce return ekle
content = re.sub(
    r'(\.method public (?!constructor|static)(\S+)\([^)]*\)([VZBSCIJFD]|L[^;]+;|\[.+?))\n(\s+\.locals\s+\d+)',
    lambda m: m.group(0) + '\n    # PATCH: IAP disabled\n    return-void' if m.group(3) == 'V' else m.group(0),
    content
)
with open(file, "w") as f:
    f.write(content)
PYEOF
        fi
    done
done

patch_manifest
patch_billing_properties

echo ""
echo "✅ Billing patch tamamlandı"
