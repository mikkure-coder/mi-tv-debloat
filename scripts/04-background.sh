#!/usr/bin/env bash
# =============================================================================
# 04-background.sh — запрещает приложениям из packages/background.txt
# работать в фоне. Приложение НЕ отключается: иконка остаётся.
# -----------------------------------------------------------------------------
# Что происходит:
#   1. ДО подключения к ТВ весь список сверяется со стоп-листом
#      (packages/keep.txt + ядро Android). Совпадение — выход, ничего не меняем.
#   2. Для каждого пакета:
#        a. проверяем, что он установлен и что это не домашний экран;
#        b. записываем ТЕКУЩУЮ настройку в backup/appops-before.txt
#           (только при первом знакомстве с пакетом — чтобы там всегда
#           лежало исходное состояние, а не наше);
#        c. `cmd appops set <пакет> RUN_ANY_IN_BACKGROUND ignore` —
#           это «Ограничить работу в фоне» из настроек Android 9+;
#        d. читаем настройку обратно и проверяем, что она применилась.
#   3. Сводка. Уже запущенный процесс доживает до перезагрузки или
#      выгрузки — поэтому после скрипта ТВ стоит перезагрузить.
#
# Запуск:
#   ./scripts/04-background.sh
#   DRY_RUN=1 ./scripts/04-background.sh   # ничего не меняет, только печатает
#
# Откат: ./scripts/99-restore.sh — вернёт настройки из backup/appops-before.txt.
# Вручную: adb shell cmd appops set <пакет> RUN_ANY_IN_BACKGROUND default
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 4: запрет работы в фоне ==="

# IP ТВ — из переменной окружения или из .tv-host.
if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
fi

BG_FILE="$PKG_DIR/background.txt"
# Исходные настройки до нашего вмешательства — основа отката в 99.
APPOPS_BEFORE="$BACKUP_DIR/appops-before.txt"
OP="RUN_ANY_IN_BACKGROUND"

if [[ ! -f "$BG_FILE" ]]; then
    err "Нет файла $BG_FILE — нечего ограничивать."
    exit 2
fi

# Тот же жёсткий запрет, что и в 02 (lib/common.sh).
check_stoplist "$BG_FILE"

ensure_adb_connected

DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
fi

INSTALLED="$(adb_shell pm list packages | tr -d '\r' | sed 's|^package:||' | sort)"

# Домашние экраны — по ответу ТВ, как в 02. Лаунчеру фон запрещать нельзя:
# он должен быть готов к кнопке Home в любой момент.
HOME_PKGS="$(adb_shell cmd package query-activities --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | grep -oE '^[[:space:]]*[A-Za-z0-9_.]+/' | tr -d ' /' | sort -u)"

# appops_mode <пакет> — текущий режим: allow / ignore / … или «default»,
# если для пакета ничего не задано (appops отвечает «No operations.»).
appops_mode() {
    local m
    # grep без совпадений вернёт ошибку — для «не задано» это нормально.
    m="$(adb_shell cmd appops get "$1" "$OP" | tr -d '\r' \
        | grep -oE "$OP: [a-z]+" | awk '{print $2}' || true)"
    echo "${m:-default}"
}

cnt_done=0
cnt_skipped=0
cnt_errors=0

while IFS= read -r pkg; do
    [[ -z "$pkg" ]] && continue

    if ! echo "$INSTALLED" | grep -qxF "$pkg"; then
        log "SKIP: $pkg — не установлен на этом ТВ"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi
    if echo "$HOME_PKGS" | grep -qxF "$pkg"; then
        warn "SKIP: $pkg — это домашний экран, ему фон запрещать нельзя"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi

    before="$(appops_mode "$pkg")"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: cmd appops set $pkg $OP ignore   (сейчас: $before)"
        continue
    fi

    # Исходное состояние пишем один раз: повторный запуск 04 не должен
    # затереть «как было» нашим же «ignore».
    if ! grep -qE "^$pkg " "$APPOPS_BEFORE" 2>/dev/null; then
        echo "$pkg $before" >> "$APPOPS_BEFORE"
    fi

    if out="$(adb_shell cmd appops set "$pkg" "$OP" ignore 2>&1)" \
        && [[ "$(appops_mode "$pkg")" == "ignore" ]]; then
        log "BG-OFF: $pkg  (было: $before)"
        cnt_done=$((cnt_done + 1))
    else
        err "FAIL: $pkg — настройка не применилась ${out:+($out)}"
        cnt_errors=$((cnt_errors + 1))
    fi
done < <(list_names "$BG_FILE")

# Android пишет настройки appops на диск не сразу, а с задержкой (до 30 мин).
# Перезагрузка раньше — и запрет теряется: так и вышло на Mi TV 4S
# 2026-10-25 после `adb reboot`. write-settings записывает их немедленно.
if [[ "$DRY_RUN" != "1" ]] && (( cnt_done > 0 )); then
    if adb_shell cmd appops write-settings >/dev/null 2>&1; then
        log "Настройки записаны на диск (appops write-settings)."
    else
        warn "Не удалось сразу записать настройки на диск — запрет может"
        warn "не пережить перезагрузку в ближайшие 30 минут."
    fi
fi

log "---"
log "Итого:"
log "  ограничено: $cnt_done"
log "  пропущено:  $cnt_skipped"
log "  ошибок:     $cnt_errors"
log "---"
log "Уже запущенные процессы доживут до выгрузки — перезагрузи ТВ."
log "Если приложение перестало нормально работать — $RUN_DIR/99-restore.sh"
