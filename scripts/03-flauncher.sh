#!/usr/bin/env bash
# =============================================================================
# 03-flauncher.sh — установка FLauncher и назначение его домашним экраном.
# -----------------------------------------------------------------------------
# FLauncher — открытый минималистичный launcher для Android TV без рекламы,
# рекомендаций и телеметрии. Исходники: https://gitlab.com/flauncher/flauncher
# В F-Droid: me.efesser.flauncher
#
# Что делает этот скрипт:
#   1. Ищет APK FLauncher в apks/ (локальная копия) или в корне репозитория.
#   2. Если локального APK нет — пытается скачать его из F-Droid.
#      Если и это не удаётся (нет интернета / F-Droid недоступен) —
#      выводит понятную инструкцию, где скачать вручную.
#   3. Устанавливает APK на ТВ через `adb install -r`.
#   4. Пытается назначить FLauncher домашним экраном через
#      `cmd package set-home-activity`.
#   5. Если автоматом не вышло — выводит инструкцию: нажми Home на пульте
#      и выбери FLauncher в системном диалоге.
#
# Запуск:
#   ./scripts/03-flauncher.sh
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 3: установка FLauncher ==="

# IP ТВ — из переменной или .tv-host
if [[ -z "$TV_HOST" && -f "$ROOT_DIR/.tv-host" ]]; then
    TV_HOST="$(cat "$ROOT_DIR/.tv-host")"
fi

ensure_adb_connected

# Каталог, куда скрипт складывает скачанный APK. Входит в .gitignore —
# в репозитории файлов *.apk быть не должно.
APK_DIR="$ROOT_DIR/apks"
mkdir -p "$APK_DIR"

# --- Шаг 1. Найти APK --------------------------------------------------------
# Приоритет:
#   1) Любой *.apk в apks/, самый свежий по времени.
#   2) Любой *.apk в корне репозитория.
#   3) Попробовать скачать из F-Droid.
APK=""

# Берём последний APK в apks/ (ls -t сортирует по времени, head -n 1 — первый).
if ls "$APK_DIR"/*.apk >/dev/null 2>&1; then
    APK="$(ls -t "$APK_DIR"/*.apk | head -n 1)"
    log "Нашёл локальный APK: $APK"
elif ls "$ROOT_DIR"/*.apk >/dev/null 2>&1; then
    APK="$(ls -t "$ROOT_DIR"/*.apk | head -n 1)"
    log "Нашёл APK в корне репозитория: $APK"
fi

# --- Шаг 2. Если APK не нашли — пробуем качнуть ------------------------------
if [[ -z "$APK" ]]; then
    log "Локального APK нет, пробую скачать из F-Droid..."

    # F-Droid публикует индекс пакетов и прямые ссылки на репо.
    # Для FLauncher прямые ссылки имеют вид:
    #   https://f-droid.org/repo/me.efesser.flauncher_<versionCode>.apk
    # Но номер версии постоянно меняется. Простейший надёжный способ:
    # сходить на страницу пакета и найти ссылку на последний APK.
    PKG_PAGE="https://f-droid.org/en/packages/me.efesser.flauncher/"
    # curl -sfL : -s = тихо, -f = ненулевой код при HTTP-ошибке, -L = идти по редиректам.
    if command -v curl >/dev/null 2>&1; then
        PAGE_HTML="$(curl -sfL "$PKG_PAGE" || echo "")"
        if [[ -n "$PAGE_HTML" ]]; then
            # Ищем в HTML первую ссылку на .apk с хоста f-droid.org/repo.
            APK_URL="$(printf '%s\n' "$PAGE_HTML" \
                | grep -oE 'https://f-droid\.org/repo/me\.efesser\.flauncher_[0-9]+\.apk' \
                | head -n 1)"
            if [[ -n "$APK_URL" ]]; then
                APK="$APK_DIR/$(basename "$APK_URL")"
                log "Скачиваю: $APK_URL"
                if curl -sfL -o "$APK" "$APK_URL"; then
                    log "ОК, скачано: $APK"
                else
                    warn "Не удалось скачать (curl вернул ошибку)."
                    APK=""
                fi
            fi
        fi
    else
        warn "curl не установлен — автоматическое скачивание пропущено."
    fi
fi

# --- Шаг 3. Если APK так и не появился — выводим инструкцию ------------------
if [[ -z "$APK" || ! -f "$APK" ]]; then
    err "APK FLauncher не найден и не скачан. Что делать:"
    err "  1. Открой на обычном компьютере страницу:"
    err "       https://f-droid.org/en/packages/me.efesser.flauncher/"
    err "     либо релизы: https://gitlab.com/flauncher/flauncher/-/releases"
    err "  2. Скачай последний APK."
    err "  3. Положи его в каталог: $APK_DIR"
    err "  4. Снова запусти: ./scripts/03-flauncher.sh"
    exit 2
fi

# --- Шаг 4. Устанавливаем APK на ТВ ------------------------------------------
# Запомним список пакетов ДО установки — чтобы потом точно знать, что добавилось.
BEFORE="$(adb_shell pm list packages | tr -d '\r' | sort)"

log "Устанавливаю $APK на ТВ..."
# adb install -r : переустановить, сохранив данные, если пакет уже стоит.
if adb_cmd install -r "$APK"; then
    log "APK успешно установлен."
else
    err "adb install завершился с ошибкой."
    exit 3
fi

# Какой пакет только что появился на ТВ? Берём разницу «после − до».
AFTER="$(adb_shell pm list packages | tr -d '\r' | sort)"
NEW_PKG="$(comm -13 <(echo "$BEFORE") <(echo "$AFTER") | sed 's|^package:||' | head -n 1)"

# Если новый пакет не появился в diff — значит, он уже был установлен раньше
# (переустановка). Fallback: ищем по шаблону имени.
if [[ -z "$NEW_PKG" ]]; then
    NEW_PKG="$(echo "$AFTER" | grep -E '^package:(me\.efesser\.flauncher|.*flauncher.*)$' | head -n 1 | sed 's|^package:||')"
fi

if [[ -z "$NEW_PKG" ]]; then
    warn "Не могу определить имя установленного пакета FLauncher."
    warn "Это не критично: установка прошла, home-activity назначь вручную"
    warn "через меню ТВ (Настройки → Приложения → Домашний экран)."
    exit 0
fi

log "Пакет FLauncher: $NEW_PKG"

# --- Шаг 5. Пытаемся назначить его home-activity -----------------------------
# Для set-home-activity нужен компонент вида "<pkg>/<активность>".
# Запрашиваем у package manager, какую активность пакет регистрирует как HOME.
HOME_COMPONENT="$(adb_shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME "$NEW_PKG" 2>/dev/null \
    | tr -d '\r' | tail -n 1)"

log "Компонент home-activity от FLauncher: ${HOME_COMPONENT:-<не определён>}"

if [[ -n "$HOME_COMPONENT" && "$HOME_COMPONENT" == *"/"* ]]; then
    if out="$(adb_shell cmd package set-home-activity "$HOME_COMPONENT" 2>&1)"; then
        log "ОК: FLauncher назначен домашним экраном."
        log "    ($out)"
    else
        warn "set-home-activity не сработал: $out"
        warn "Это нормально на некоторых прошивках Mi TV."
        warn "Переходим к ручному назначению ↓"
        HOME_COMPONENT=""
    fi
fi

# --- Шаг 6. Ручной fallback --------------------------------------------------
if [[ -z "$HOME_COMPONENT" ]]; then
    log "---"
    log "Назначь FLauncher домашним экраном вручную:"
    log "  1. Нажми Home на пульте телевизора."
    log "  2. ТВ покажет диалог выбора лаунчера."
    log "  3. Выбери 'FLauncher' и 'Всегда' (или 'Запомнить выбор')."
    log ""
    log "Если диалог не появился:"
    log "  Настройки → Приложения → По умолчанию → Домашний экран → FLauncher."
fi

log "---"
log "Готово. Проверка: нажми Home на пульте."
log "Если всё хорошо — можно раскомментировать 'com.mitv.tvhome' в"
log "packages/debloat.txt и перезапустить ./scripts/02-debloat.sh,"
log "чтобы окончательно отключить PatchWall."
