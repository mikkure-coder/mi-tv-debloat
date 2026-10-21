#!/usr/bin/env bash
# =============================================================================
# 99-restore.sh — откат всего, что сделали 02-debloat.sh и 03-flauncher.sh.
# -----------------------------------------------------------------------------
# Что делает:
#   1. Для каждого пакета из packages/debloat.txt выполняет
#      `pm enable <пакет>` — возвращает его в активное состояние.
#   2. (Опционально, после подтверждения) удаляет FLauncher с ТВ.
#   3. Пытается вернуть PatchWall (com.mitv.tvhome) как домашний экран.
#
# Запуск:
#   ./scripts/99-restore.sh                          — только включить пакеты
#   REMOVE_FLAUNCHER=1 ./scripts/99-restore.sh       — ещё и удалить FLauncher
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Откат деблоата ==="

# IP ТВ — из переменной или из .tv-host.
if [[ -z "$TV_HOST" && -f "$ROOT_DIR/.tv-host" ]]; then
    TV_HOST="$(cat "$ROOT_DIR/.tv-host")"
fi

ensure_adb_connected

DEBLOAT_FILE="$ROOT_DIR/packages/debloat.txt"

if [[ ! -f "$DEBLOAT_FILE" ]]; then
    err "Нет файла $DEBLOAT_FILE — непонятно, что включать."
    exit 2
fi

# --- Шаг 1. Включаем всё, что было в списке деблоата -----------------------
cnt_enabled=0
cnt_skipped=0
cnt_errors=0

INSTALLED="$(adb_shell pm list packages | tr -d '\r' | sed 's|^package:||' | sort)"

while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    # Срезаем комментарии и пробелы.
    line="${raw_line%%#*}"
    line="$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

    [[ -z "$line" ]] && continue

    pkg="$line"

    # Если пакет не установлен — включать нечего.
    if ! echo "$INSTALLED" | grep -qxF "$pkg"; then
        log "SKIP: $pkg — не установлен"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi

    # pm enable возвращает пакет в состояние «включён». Операция идемпотентная:
    # если пакет уже включён, pm просто ответит «enabled».
    if out="$(adb_shell pm enable "$pkg" 2>&1)"; then
        log "ON:   $pkg  ($out)"
        cnt_enabled=$((cnt_enabled + 1))
    else
        err "FAIL: $pkg — $out"
        cnt_errors=$((cnt_errors + 1))
    fi
done < "$DEBLOAT_FILE"

log "---"
log "Включено:   $cnt_enabled"
log "Пропущено:  $cnt_skipped"
log "Ошибок:     $cnt_errors"

# --- Шаг 2. Опционально: удаление FLauncher --------------------------------
# По умолчанию FLauncher остаётся на ТВ — чтобы не терять его данные.
# Если нужно полностью откатить ТВ в исходное состояние, запусти:
#   REMOVE_FLAUNCHER=1 ./scripts/99-restore.sh
REMOVE_FLAUNCHER="${REMOVE_FLAUNCHER:-0}"
if [[ "$REMOVE_FLAUNCHER" == "1" ]]; then
    FL_PKG="me.efesser.flauncher"
    if echo "$INSTALLED" | grep -qxF "$FL_PKG"; then
        log "Удаляю FLauncher ($FL_PKG)..."
        if out="$(adb_cmd uninstall "$FL_PKG" 2>&1)"; then
            log "FLauncher удалён ($out)"
        else
            err "Не удалось удалить FLauncher: $out"
        fi
    else
        log "FLauncher не установлен — пропускаю удаление."
    fi
fi

# --- Шаг 3. Пробуем вернуть PatchWall как home ------------------------------
# Если PatchWall был отключён и теперь снова включён, он должен автоматически
# стать выбором по умолчанию (у Xiaomi он единственный помечен как home-app).
# Но подстрахуемся: явно скажем системе использовать его.
PW="com.mitv.tvhome"
if echo "$INSTALLED" | grep -qxF "$PW"; then
    PW_COMPONENT="$(adb_shell cmd package resolve-activity --brief \
        -c android.intent.category.HOME "$PW" 2>/dev/null \
        | tr -d '\r' | tail -n 1)"
    if [[ -n "$PW_COMPONENT" && "$PW_COMPONENT" == *"/"* ]]; then
        adb_shell cmd package set-home-activity "$PW_COMPONENT" >/dev/null 2>&1 || true
        log "Домашний экран возвращён на PatchWall: $PW_COMPONENT"
    fi
fi

log "---"
log "Готово. Нажми Home на пульте и проверь, что интерфейс вернулся."
log "Если что-то не так — на крайний случай сделай фабричный сброс через"
log "'Настройки → Настройки устройства → Сброс'."
