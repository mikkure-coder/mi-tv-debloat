#!/usr/bin/env bash
# =============================================================================
# 05-components.sh — отключает отдельные части (компоненты) приложений из
# packages/components.txt. Само приложение и его иконка остаются.
# -----------------------------------------------------------------------------
# Зачем: некоторые приложения сами стартуют при каждой загрузке ТВ, потому
# что ловят сигнал BOOT_COMPLETED. Запрет работы в фоне (04) этот сигнал не
# блокирует. Отключение одного получателя сигнала — блокирует.
#
# Что происходит:
#   1. ДО подключения к ТВ пакеты из списка сверяются со стоп-листом
#      (packages/keep.txt + ядро Android). Совпадение — выход, ничего не меняем.
#   2. Для каждой строки «<пакет>/<компонент>»:
#        a. проверяем, что пакет установлен и это не домашний экран;
#        b. записываем исходное состояние компонента (default / enabled /
#           disabled) в backup/components-before.txt — один раз, при первом
#           знакомстве, чтобы там всегда лежало состояние «до нас»;
#        c. `pm disable --user 0 <пакет>/<компонент>`;
#        d. проверяем, что компонент появился в списке отключённых.
#
# Запуск:
#   ./scripts/05-components.sh
#   DRY_RUN=1 ./scripts/05-components.sh   # ничего не меняет, только печатает
#
# Откат: ./scripts/99-restore.sh — вернёт состояния из
#        backup/components-before.txt.
# Вручную: adb shell pm default-state --user 0 <пакет>/<компонент>
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 5: отключение отдельных компонентов ==="

# IP ТВ — из переменной окружения или из .tv-host.
if [[ -z "$TV_HOST" && -f "$ROOT_DIR/.tv-host" ]]; then
    TV_HOST="$(cat "$ROOT_DIR/.tv-host")"
fi

COMP_FILE="$ROOT_DIR/packages/components.txt"
# Исходные состояния до нашего вмешательства — основа отката в 99.
COMP_BEFORE="$BACKUP_DIR/components-before.txt"

if [[ ! -f "$COMP_FILE" ]]; then
    err "Нет файла $COMP_FILE — нечего отключать."
    exit 2
fi

# Тот же жёсткий запрет, что и в 02: сверяем пакетную часть каждой строки.
check_stoplist <(list_names "$COMP_FILE" | cut -d/ -f1) "components.txt"

ensure_adb_connected

DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
fi

INSTALLED="$(adb_shell pm list packages | tr -d '\r' | sed 's|^package:||' | sort)"
HOME_PKGS="$(adb_shell cmd package query-activities --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | grep -oE '^[[:space:]]*[A-Za-z0-9_.]+/' | tr -d ' /' | sort -u)"

# comp_state <пакет> <класс> — disabled / enabled / default. В dumpsys package
# компонент перечислен под «disabledComponents:» или «enabledComponents:»,
# если его состояние меняли; если нигде нет — он в состоянии по умолчанию.
comp_state() {
    local dump
    dump="$(adb_shell dumpsys package "$1" | tr -d '\r')"
    if echo "$dump" | sed -n '/disabledComponents:/,/enabledComponents:/p' \
        | grep -qE "^[[:space:]]+$2\$"; then
        echo disabled
    elif echo "$dump" | sed -n '/enabledComponents:/,/^[^ ]/p' \
        | grep -qE "^[[:space:]]+$2\$"; then
        echo enabled
    else
        echo default
    fi
}

cnt_done=0
cnt_skipped=0
cnt_errors=0

while IFS= read -r comp; do
    [[ -z "$comp" ]] && continue
    pkg="${comp%%/*}"
    cls="${comp#*/}"

    if [[ "$pkg" == "$comp" || -z "$cls" ]]; then
        err "FAIL: «$comp» — нужна строка вида <пакет>/<компонент>"
        cnt_errors=$((cnt_errors + 1))
        continue
    fi
    if ! echo "$INSTALLED" | grep -qxF "$pkg"; then
        log "SKIP: $comp — пакет не установлен на этом ТВ"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi
    if echo "$HOME_PKGS" | grep -qxF "$pkg"; then
        warn "SKIP: $comp — это часть домашнего экрана, не трогаю"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi

    before="$(comp_state "$pkg" "$cls")"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: pm disable --user 0 $comp   (сейчас: $before)"
        continue
    fi

    # Исходное состояние пишем один раз — повторный запуск не должен
    # затереть «как было» нашим же «disabled».
    if ! grep -qF "$comp " "$COMP_BEFORE" 2>/dev/null; then
        echo "$comp $before" >> "$COMP_BEFORE"
    fi

    if out="$(adb_shell pm disable --user 0 "$comp" 2>&1)" \
        && [[ "$(comp_state "$pkg" "$cls")" == "disabled" ]]; then
        log "OFF: $comp  (было: $before)"
        cnt_done=$((cnt_done + 1))
    else
        err "FAIL: $comp — не отключился ${out:+($out)}"
        cnt_errors=$((cnt_errors + 1))
    fi
done < <(list_names "$COMP_FILE")

log "---"
log "Итого:"
log "  отключено:  $cnt_done"
log "  пропущено:  $cnt_skipped"
log "  ошибок:     $cnt_errors"
log "---"
log "Проверка: перезагрузи ТВ и посмотри, стартует ли приложение само."
log "Если приложение перестало нормально работать — ./scripts/99-restore.sh"
