#!/usr/bin/env bash
# =============================================================================
# 00-prepare.sh — подготовка: убеждаемся, что всё готово к деблоату.
# -----------------------------------------------------------------------------
# Что делает:
#   1. Проверяет, что в системе установлен adb.
#   2. Если не задан TV_HOST — спрашивает IP телевизора у пользователя.
#   3. Подключается к ТВ по сети (adb connect).
#   4. Выводит короткую сводку: модель, Android-версия, какой сейчас launcher.
#   5. Сохраняет TV_HOST в файл .tv-host, чтобы следующие скрипты
#      брали IP оттуда и не спрашивали заново.
#
# Запуск:
#   ./scripts/00-prepare.sh
#   TV_HOST=192.168.0.42 ./scripts/00-prepare.sh   # без интерактивного ввода
# =============================================================================

# Подключаем общие функции (log, ensure_adb_connected и пр.).
source "$(dirname "$0")/lib/common.sh"

log "=== Этап 0: подготовка ==="

# --- Шаг 1. Проверяем, что adb вообще установлен -----------------------------
require_cmd adb

# --- Шаг 2. Запрашиваем IP телевизора, если он не задан ----------------------
# Файл .tv-host в корне проекта — память между запусками: один раз ввёл IP,
# дальше все скрипты (01-backup, 02-debloat, 03-flauncher, 99-restore)
# берут его оттуда автоматически.
HOST_FILE="$ROOT_DIR/.tv-host"

if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
    log "IP телевизора взят из $HOST_FILE: $TV_HOST"
fi

if [[ -z "$TV_HOST" ]]; then
    # read -r — читаем строку из терминала, не интерпретируя обратные слэши.
    read -r -p "Введи IP телевизора (Настройки → Сеть → Информация): " TV_HOST
fi

# Простейшая проверка формата IP: четыре числа через точку.
# Это не защита от злоумышленника, а защита от опечатки вроде '192.168..1'.
if ! [[ "$TV_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    err "Это не похоже на IPv4-адрес: '$TV_HOST'"
    exit 2
fi

# Сохраняем IP для следующих скриптов.
echo "$TV_HOST" > "$HOST_FILE"
log "IP телевизора сохранён в $HOST_FILE"

# --- Шаг 3. Пробуем подключиться ---------------------------------------------
# Функция ensure_adb_connected сама расскажет, что пошло не так, если не вышло.
ensure_adb_connected

# --- Шаг 4. Короткая справка о телевизоре -----------------------------------
# Берём свойства системы через getprop. Это безопасный read-only запрос —
# ничего в ТВ не меняет.
MODEL="$(adb_shell getprop ro.product.model | tr -d '\r')"
BRAND="$(adb_shell getprop ro.product.brand | tr -d '\r')"
AVER="$(adb_shell getprop ro.build.version.release | tr -d '\r')"
SDK="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
FP="$(adb_shell getprop ro.build.fingerprint | tr -d '\r')"

log "Устройство: $BRAND $MODEL"
log "Android:    $AVER (SDK $SDK)"
log "Fingerprint: $FP"

# Текущий домашний экран (home-activity). Это важно: мы должны увидеть,
# что по умолчанию стоит PatchWall (com.mitv.tvhome), и позже убедиться,
# что после всех манипуляций launcher переключился на FLauncher.
log "---"
log "Текущий домашний экран (home-activity):"
adb_shell cmd package resolve-activity --brief -c android.intent.category.HOME \
    | tail -n 1 | tee -a "$LOG_FILE"

# Проверим, установлен ли стандартный Android TV launcher — он нужен как
# «страховочный» вариант, если что-то пойдёт не так с PatchWall.
log "---"
if adb_shell pm list packages | grep -qE 'com.google.android.(tvlauncher|leanbacklauncher)'; then
    log "ОК: на ТВ есть стандартный Android TV launcher (fallback)."
else
    warn "На ТВ нет штатного Android TV launcher!"
    warn "Это НЕ блокирует работу, но ПОСЛЕ деблоата перед отключением"
    warn "PatchWall обязательно должен быть установлен FLauncher"
    warn "(запусти 03-flauncher.sh)."
fi

log "---"
log "Готово. Можно переходить к 01-backup.sh"
