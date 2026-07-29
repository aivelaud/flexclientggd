#!/bin/bash
# ============================================================
# Patch: Tüm Kıyafet/Kozmetik Kilidini Aç
# Unity IL2CPP oyunlarda oyun mantığı libil2cpp.so içinde
# derlenmiş olduğundan smali katmanından yapılabilecek
# değişiklikler sınırlıdır. Bu script smali'de erişilebilen
# tüm unlock noktalarını patch'ler.
# ============================================================
set -e
DECODED_DIR="${1:-decoded}"

echo "🔧 Cosmetics unlock patch başlıyor: $DECODED_DIR"

SMALI_DIRS=$(find "$DECODED_DIR" -maxdepth 1 -type d -name "smali*")

# --------------------------------------------------------
# 1. SharedPreferences'taki unlock flag'lerini zorla
#    "isUnlocked", "isPurchased", "isOwned" gibi
#    boolean değer döndüren metodları true yap
# --------------------------------------------------------
patch_unlock_methods() {
    local file="$1"
    python3 - "$file" <<'PYEOF'
import sys, re

file = sys.argv[1]
with open(file, "r") as f:
    content = f.read()

original = content

# Pattern: isUnlocked/isPurchased/isOwned gibi metodlar boolean döndürüyor
# Return type Z (boolean) olan metodları bul
unlock_keywords = [
    'isUnlocked', 'isPurchased', 'isOwned', 'hasPurchased',
    'isItemOwned', 'hasItem', 'isEquipped', 'canEquip',
    'hasCostume', 'hasCosmetic', 'isAvailable', 'isFree',
    'isUnlockable', 'checkUnlocked', 'getIsUnlocked',
]

for kw in unlock_keywords:
    # Method declaration + body pattern
    pattern = rf'(\.method (?:public |private |protected |static )*{re.escape(kw)}\([^)]*\)Z\n)(.*?)(\.end method)'
    def make_true_return(m):
        method_decl = m.group(1)
        body = m.group(2)
        end = m.group(3)
        # locals satırını bul
        locals_match = re.search(r'\.locals (\d+)', body)
        locals_count = max(int(locals_match.group(1)) if locals_match else 0, 1)
        new_body = f'    .locals {locals_count}\n    # PATCH: Always unlocked\n    const/4 v0, 0x1\n    return v0\n'
        return method_decl + new_body + end
    
    new_content = re.sub(pattern, make_true_return, content, flags=re.DOTALL | re.IGNORECASE)
    if new_content != content:
        content = new_content

if content != original:
    with open(file, "w") as f:
        f.write(content)
    print(f"    Patched unlock methods in: {file}")
PYEOF
}

# --------------------------------------------------------
# 2. Google Play Games achievement/unlock metodlarını
#    her zaman başarılı döndür
# --------------------------------------------------------
patch_gpg_achievements() {
    local file="$1"
    if grep -q "ACHIEVEMENT_UNLOCK\|unlockAchievement\|revealAchievement" "$file" 2>/dev/null; then
        echo "  → GPG achievement patch: $file"
        python3 - "$file" <<'PYEOF'
import sys, re
file = sys.argv[1]
with open(file) as f:
    content = f.read()
# isAchievementUnlocked tipi metodlar true dönsün
content = re.sub(
    r'(\.method .*isAchievementUnlocked.*Z\n)(.*?)(\.end method)',
    lambda m: m.group(1) + '    .locals 1\n    const/4 v0, 0x1\n    return v0\n' + m.group(3),
    content, flags=re.DOTALL
)
with open(file, "w") as f:
    f.write(content)
PYEOF
    fi
}

# --------------------------------------------------------
# 3. Boot.config içinde development mode aç (bazı
#    Unity oyunlarda unlock debug flag'i açar)
# --------------------------------------------------------
patch_boot_config() {
    local boot="$DECODED_DIR/assets/bin/Data/boot.config"
    if [ -f "$boot" ]; then
        echo "  → boot.config patch"
        # development build flag yoksa ekle
        grep -q "development-build" "$boot" || echo "development-build=1" >> "$boot"
        grep -q "wait-for-managed-debugger" "$boot" || echo "wait-for-managed-debugger=0" >> "$boot"
        echo "    boot.config patched"
    fi
}

# --------------------------------------------------------
# 4. Tüm smali dosyalarını tara
# --------------------------------------------------------
echo "📂 Smali dosyaları taranıyor..."
PATCHED=0

for smali_dir in $SMALI_DIRS; do
    # Sadece oyun mantığıyla ilgili smali'leri işle
    find "$smali_dir" -type f -name "*.smali" | while read smali_file; do
        # Dosya adı veya path'te unlock/cosmetic kelime içeriyorsa
        if echo "$smali_file" | grep -qiE "unlock|cosmetic|costume|outfit|wardrobe|skin|item|shop|store|inventory|equip|owned"; then
            patch_unlock_methods "$smali_file" 2>/dev/null || true
        fi
        
        # GPG achievements
        patch_gpg_achievements "$smali_file" 2>/dev/null || true
    done
done

patch_boot_config

echo ""
echo "ℹ️  NOT: Bu oyun Unity IL2CPP kullanmaktadır."
echo "   Kıyafet mantığının büyük kısmı libil2cpp.so içinde derlenmiştir."
echo "   Tam unlock için libil2cpp.so binary patch'i gerekebilir."
echo ""
echo "✅ Cosmetics patch tamamlandı"
