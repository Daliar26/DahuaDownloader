#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

PROJECT="$HOME/DahuaDownloader"
SDK_JAR="$PROJECT/app/libs/INetSDK.jar"
JNI_DIR="$PROJECT/app/src/main/jniLibs/arm64-v8a"
WORKFLOW="$PROJECT/.github/workflows/build.yml"
BACKUP="$HOME/DahuaBuilder/backups"
LOG="$PROJECT/DEPLOY_GITHUB.log"
REMOTE="https://github.com/Daliar26/DahuaDownloader.git"

mkdir -p "$BACKUP" "$PROJECT/.github/workflows"
exec > >(tee "$LOG") 2>&1

trap 'echo; echo "[ОШИБКА] Строка $LINENO. Подробности: $LOG"; exit 1' ERR

echo "=================================================="
echo " DAHUA DOWNLOADER — GITHUB DEPLOY"
echo " Сборка APK будет выполняться на GitHub Actions"
echo "=================================================="

fail() {
    echo
    echo "[ОШИБКА] $*"
    exit 1
}

ok() {
    echo "[OK] $*"
}

warn() {
    echo "[ВНИМАНИЕ] $*"
}

[ -d "$PROJECT" ] || fail "Не найден проект: $PROJECT"
cd "$PROJECT"

echo
echo "1. ПРОВЕРКА ИНСТРУМЕНТОВ"

for cmd in git java javap python; do
    command -v "$cmd" >/dev/null 2>&1 || fail "Не найдена команда: $cmd"
done

java -version 2>&1 | head -3
git --version
ok "Основные инструменты доступны"

if [ -n "$(git status --porcelain 2>/dev/null || true)" ]; then
    warn "Есть локальные изменения — они будут сохранены в Git"
fi

echo
echo "2. РЕЗЕРВНОЕ КОПИРОВАНИЕ СТАРЫХ BACKUP-ФАЙЛОВ"

for f in .gitignore.backup app/build.gradle.kts.backup; do
    if [ -f "$PROJECT/$f" ]; then
        dest="$BACKUP/$(date +%Y%m%d_%H%M%S)_$(basename "$f")"
        cp -p "$PROJECT/$f" "$dest"
        echo "Сохранено: $dest"
    fi
done

echo
echo "3. ПРОВЕРКА ПРОЕКТА И SDK"

[ -f "$PROJECT/settings.gradle.kts" ] || fail "Нет settings.gradle.kts"
[ -f "$PROJECT/build.gradle.kts" ] || fail "Нет корневого build.gradle.kts"
[ -f "$PROJECT/app/build.gradle.kts" ] || fail "Нет app/build.gradle.kts"
[ -f "$PROJECT/app/src/main/AndroidManifest.xml" ] || fail "Нет AndroidManifest.xml"
[ -f "$PROJECT/app/src/main/java/ru/dahuatools/downloader/MainActivity.kt" ] || fail "Нет MainActivity.kt"

[ -s "$SDK_JAR" ] || fail "Не найден SDK JAR: $SDK_JAR"
[ -d "$JNI_DIR" ] || fail "Не найдена папка ARM64 библиотек: $JNI_DIR"

ok "Структура Android-проекта найдена"
ok "SDK JAR найден"

echo
echo "4. ПРОВЕРКА ВСЕХ 13 НАТИВНЫХ БИБЛИОТЕК"

EXPECTED=(
libavnetsdk.so
libconfigsdk.so
libcrypto.so
libhwdec.so
libIvsDrawer.so
libjninetsdk.so
libjniplay.so
libjnismartconfig.so
libnetsdk.so
libplay.so
libssl.so
libStreamConvertor.so
libToUProxy.so
)

for name in "${EXPECTED[@]}"; do
    file="$JNI_DIR/$name"
    [ -s "$file" ] || fail "Отсутствует библиотека: $name"
    printf "%-25s %10s bytes\n" "$name" "$(wc -c < "$file")"
done

COUNT="$(find "$JNI_DIR" -maxdepth 1 -type f -name '*.so' | wc -l | tr -d ' ')"
[ "$COUNT" -eq 13 ] || fail "Ожидалось 13 .so, найдено: $COUNT"
ok "Все 13 библиотек на месте"

echo
echo "5. ПРОВЕРКА SDK API"

JAVAP="$(javap -classpath "$SDK_JAR" -p com.company.NetSDK.INetSDK 2>&1)" \
    || fail "Не удалось прочитать класс INetSDK"

for method in Init Cleanup LoginWithHighLevelSecurity Logout DownloadByTimeEx StopDownload SetDeviceMode; do
    printf "%s\n" "$JAVAP" | grep -F "$method" >/dev/null || fail "В SDK не найден метод $method"
    ok "Метод SDK: $method"
done

javap -classpath "$SDK_JAR" -p \
    com.company.NetSDK.CB_fDisConnect >/dev/null \
    || fail "Не найден callback отключения"

javap -classpath "$SDK_JAR" -p \
    com.company.NetSDK.CB_fTimeDownLoadPosCallBack >/dev/null \
    || fail "Не найден callback загрузки"

javap -classpath "$SDK_JAR" -p \
    com.company.NetSDK.NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY >/dev/null \
    || fail "Не найдена структура авторизации"

javap -classpath "$SDK_JAR" -p \
    com.company.NetSDK.NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY >/dev/null \
    || fail "Не найдена структура ответа авторизации"

echo
echo "Информация о загрузчике нативных библиотек SDK:"
javap -classpath "$SDK_JAR" -c -p com.company.NetSDK.INetSDK 2>/dev/null \
    | grep -A100 -B3 'LoadLibrarys' | head -110 || true

echo
echo "6. ПРОВЕРКА РАЗМЕРОВ ФАЙЛОВ ДЛЯ GITHUB"

LFS_NEEDED=0

while IFS= read -r -d '' f; do
    SIZE="$(wc -c < "$f")"
    MB=$((SIZE / 1024 / 1024))
    echo "$f — ${MB} MB"

    if [ "$SIZE" -ge 95000000 ]; then
        LFS_NEEDED=1
    fi
done < <(find "$JNI_DIR" -type f -name '*.so' -print0)

JAR_SIZE="$(wc -c < "$SDK_JAR")"
echo "INetSDK.jar — $((JAR_SIZE / 1024 / 1024)) MB"

if [ "$JAR_SIZE" -ge 95000000 ]; then
    LFS_NEEDED=1
fi

if [ "$LFS_NEEDED" -eq 1 ]; then
    command -v git-lfs >/dev/null 2>&1 \
        || fail "Есть файл около 100 MB или больше. Установи Git LFS в Termux и запусти скрипт повторно."

    git lfs install
    find "$JNI_DIR" -type f -name '*.so' -size +90M -print0 \
        | while IFS= read -r -d '' f; do git lfs track "$f"; done

    if [ "$JAR_SIZE" -ge 95000000 ]; then
        git lfs track "app/libs/INetSDK.jar"
    fi

    ok "Настроен Git LFS для больших файлов"
else
    ok "Файлы не превышают безопасный порог GitHub"
fi

echo
echo "7. ПОДГОТОВКА GITIGNORE"

cat > "$PROJECT/.gitignore" <<'EOF'
.gradle/
**/build/
local.properties
.idea/
*.iml
.DS_Store
secrets.properties
*.jks
*.keystore
*.log
EOF

echo
echo "8. НАСТРОЙКА GITHUB ACTIONS"

cat > "$WORKFLOW" <<'YAML'
name: Build Dahua Downloader APK

on:
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    name: Build Android APK
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Checkout source
        uses: actions/checkout@v4
        with:
          lfs: true

      - name: Verify Dahua SDK files
        shell: bash
        run: |
          set -euo pipefail
          test -s app/libs/INetSDK.jar
          test -d app/src/main/jniLibs/arm64-v8a
          count=$(find app/src/main/jniLibs/arm64-v8a -maxdepth 1 -type f -name '*.so' | wc -l)
          echo "Native libraries: $count"
          test "$count" -eq 13

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools platforms;android-35 build-tools;35.0.0'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Build debug APK
        run: gradle --no-daemon clean assembleDebug

      - name: Verify APK
        shell: bash
        run: |
          set -euo pipefail
          APK="app/build/outputs/apk/debug/app-debug.apk"
          test -s "$APK"
          ls -lh "$APK"
          sha256sum "$APK"

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: DahuaDownloader-APK
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 30
YAML

ok "GitHub Actions workflow создан"

echo
echo "9. ПРОВЕРКА REMOTE И ВЕТКИ"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    fail "Папка не является Git-репозиторием"
fi

git remote get-url origin >/dev/null 2>&1 \
    || git remote add origin "$REMOTE"

CURRENT_REMOTE="$(git remote get-url origin)"
echo "Remote: $CURRENT_REMOTE"

case "$CURRENT_REMOTE" in
    *Daliar26/DahuaDownloader.git*) ;;
    *) fail "Настроен неожиданный Git remote. Ожидался $REMOTE" ;;
esac

git branch -M main

echo
echo "10. ДОБАВЛЕНИЕ SDK В РЕПОЗИТОРИЙ"

git add -A
git add -f "$SDK_JAR"
git add -f "$JNI_DIR"

if [ -f "$PROJECT/.gitattributes" ]; then
    git add .gitattributes
fi

echo
echo "Изменения перед отправкой:"
git status --short

echo
echo "11. ПРОВЕРКА GIT LFS"

if git lfs version >/dev/null 2>&1; then
    git lfs ls-files || true
fi

echo
echo "12. СОЗДАНИЕ КОММИТА"

if ! git diff --cached --quiet; then
    git config user.name "Dahua Downloader Builder"
    git config user.email "builder@users.noreply.github.com"
    git commit -m "Prepare Dahua Downloader for GitHub Actions build"
else
    echo "Новых изменений для коммита нет"
fi

echo
echo "13. ОТПРАВКА НА GITHUB"

git push -u origin main

ok "Исходный проект отправлен на GitHub"

echo
echo "14. РЕЗУЛЬТАТ"

echo "Репозиторий:"
echo "https://github.com/Daliar26/DahuaDownloader"

echo
echo "Сборки GitHub Actions:"
echo "https://github.com/Daliar26/DahuaDownloader/actions"

echo
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI обнаружен. Ожидаем сборку..."
    gh run watch --exit-status || {
        warn "GitHub Actions завершился с ошибкой. Открой ссылку Actions выше."
        exit 1
    }
    echo
    gh run list --limit 3
else
    echo "GitHub CLI не настроен — сборка выполняется на GitHub."
    echo "Открой Actions по ссылке выше и дождись зелёного статуса."
fi

echo
echo "=================================================="
echo " ОТПРАВКА ЗАВЕРШЕНА"
echo " APK собирается на GitHub, не на телефоне."
echo "=================================================="
echo "Лог: $LOG"
