#!/usr/bin/env bash
# =============================================================================
# 06-settings.sh — меняет системные настройки Android по packages/settings.txt.
# -----------------------------------------------------------------------------
# Что происходит:
#   1. ДО подключения к ТВ каждая строка сверяется с разрешённым списком
#      ключей ALLOWED (ниже). Хоть одна чужая — выход, ничего не меняем.
#   2. Для каждой строки «<раздел> <ключ> <значение>»:
#        a. читаем текущее значение (`settings get`);
#        b. записываем его в backup/settings-before.txt — один раз, при
#           первом знакомстве, чтобы там всегда лежало состояние «до нас»
#           («null» = ключ не был задан);
#        c. `settings put <раздел> <ключ> <значение>` и читаем обратно.
#
# Запуск:
#   ./scripts/06-settings.sh
#   DRY_RUN=1 ./scripts/06-settings.sh   # ничего не меняет, только печатает
#
# Откат: ./scripts/99-restore.sh — вернёт значения из backup/settings-before.txt.
# Вручную: adb shell settings put global window_animation_scale 1.0
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 6: системные настройки ==="

# IP ТВ — из переменной окружения или из .tv-host.
if [[ -z "$TV_HOST" && -f "$ROOT_DIR/.tv-host" ]]; then
    TV_HOST="$(cat "$ROOT_DIR/.tv-host")"
fi

SET_FILE="$ROOT_DIR/packages/settings.txt"
# Исходные значения до нашего вмешательства — основа отката в 99.
SET_BEFORE="$BACKUP_DIR/settings-before.txt"

# Разрешённые «раздел ключ». Только то, что безопасно и обратимо: ни сети,
# ни ADB, ни экрана блокировки, ни домашнего экрана здесь быть не должно.
ALLOWED="
global window_animation_scale
global transition_animation_scale
global animator_duration_scale
"

if [[ ! -f "$SET_FILE" ]]; then
    err "Нет файла $SET_FILE — нечего менять."
    exit 2
fi

# --- Проверка по разрешённому списку — до подключения к ТВ ------------------
blocked=0
while read -r ns key value; do
    [[ -z "$ns" ]] && continue
    if [[ -z "$key" || -z "$value" ]]; then
        err "ЗАПРЕЩЕНО: «$ns $key $value» — нужна строка <раздел> <ключ> <значение>"
        blocked=$((blocked + 1))
    elif ! echo "$ALLOWED" | grep -qxF "$ns $key"; then
        err "ЗАПРЕЩЕНО: $ns $key — нет в разрешённом списке 06-settings.sh"
        blocked=$((blocked + 1))
    fi
done < <(list_names "$SET_FILE")
if (( blocked > 0 )); then
    err "Ничего не меняю. Убери эти строки из packages/settings.txt"
    err "или, если ключ точно безопасен, добавь его в ALLOWED с обоснованием."
    exit 2
fi
log "Проверка: все ключи в settings.txt из разрешённого списка."

ensure_adb_connected

DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
fi

cnt_done=0
cnt_errors=0

while read -r ns key value; do
    [[ -z "$ns" ]] && continue
    before="$(adb_shell settings get "$ns" "$key" | tr -d '\r')"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: settings put $ns $key $value   (сейчас: $before)"
        continue
    fi

    # Исходное значение пишем один раз — повторный запуск не должен
    # затереть «как было» нашим же значением.
    if ! grep -qE "^$ns $key " "$SET_BEFORE" 2>/dev/null; then
        echo "$ns $key ${before:-null}" >> "$SET_BEFORE"
    fi

    adb_shell settings put "$ns" "$key" "$value"
    now="$(adb_shell settings get "$ns" "$key" | tr -d '\r')"
    if [[ "$now" == "$value" ]]; then
        log "SET: $ns $key = $value  (было: $before)"
        cnt_done=$((cnt_done + 1))
    else
        err "FAIL: $ns $key — после записи читается «$now», а не «$value»"
        cnt_errors=$((cnt_errors + 1))
    fi
done < <(list_names "$SET_FILE")

log "---"
log "Итого:"
log "  изменено:  $cnt_done"
log "  ошибок:    $cnt_errors"
log "Если что-то не так — ./scripts/99-restore.sh"
