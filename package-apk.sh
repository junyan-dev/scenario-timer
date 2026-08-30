#!/usr/bin/env bash
# 打包 APK（自动小版本升级）
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

BUILD_FILE="app/build.gradle.kts"
VERSION_CODE=$(grep versionCode "$BUILD_FILE" | awk -F'=' '{gsub(/[[:space:]]/, "", $2); print $2}')
VERSION_NAME=$(grep versionName "$BUILD_FILE" | awk -F'"' '{print $2}')

# 解析版本号，支持 x.y 和 x.y.z
IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION_NAME"
PATCH=${PATCH:-0}
NEW_PATCH=$((PATCH + 1))
if [[ -z "$MINOR" ]]; then
    MINOR=0
    NEW_VERSION_NAME="${MAJOR}.${MINOR}.${NEW_PATCH}"
else
    NEW_VERSION_NAME="${MAJOR}.${MINOR}.${NEW_PATCH}"
fi
NEW_VERSION_CODE=$((VERSION_CODE + 1))

echo "当前版本: $VERSION_NAME (versionCode=$VERSION_CODE)"

# 小版本升级
sed -i '' "s/versionCode = $VERSION_CODE/versionCode = $NEW_VERSION_CODE/g" "$BUILD_FILE"
ESCAPED_VERSION=$(echo "$VERSION_NAME" | sed 's/\./\\./g')
ESCAPED_NEW_VERSION=$(echo "$NEW_VERSION_NAME" | sed 's/\./\\./g')
sed -i '' "s/versionName = \"$ESCAPED_VERSION\"/versionName = \"$ESCAPED_NEW_VERSION\"/g" "$BUILD_FILE"
echo "升级至: $NEW_VERSION_NAME (versionCode=$NEW_VERSION_CODE)"

# 清理并构建
echo "清理并构建..."
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-tmp}" ./gradlew clean assembleDebug

SRC_APK="app/build/outputs/apk/debug/app-debug.apk"
VERSIONED_APK="app/build/outputs/apk/debug/app-debug-$NEW_VERSION_NAME.apk"

cp "$SRC_APK" "$VERSIONED_APK"
echo ""
echo "APK 已生成: $VERSIONED_APK"
echo "完成 ✓"
