#!/usr/bin/env bash
# =============================================================================
# 07-theme.sh — ставит тему лаунчеру CleanYourTV (com.gathin.tvhome).
# -----------------------------------------------------------------------------
# Тема — это config.json (+ необязательно wallpaper.jpg) в данных САМОГО
# лаунчера. Система и другие приложения не затрагиваются.
#
# Как файлы попадают в лаунчер: adb push во временный /data/local/tmp, затем
#   content write --uri content://com.gathin.tvhome.theme/<файл> < <временный>
# Этот канал лаунчер открывает только для adb shell (право
# WRITE_SECURE_SETTINGS) и только для файлов темы: config.json, wallpaper.jpg,
# theme_revision, wallpapers/*. Файл theme_revision со случайной строкой
# говорит лаунчеру «тема сменилась» — он сбрасывает свои выборы в пользу темы.
# После записи лаунчер перезапускается (am force-stop + Home).
#
# Мы НЕ используем их tools/theme.sh: он дёргает службу специальных
# возможностей лаунчера, которую мы не включали.
#
# Запуск:
#   ./scripts/07-theme.sh                    # тема устройства (theme/) или themes/glass-mitv
#   ./scripts/07-theme.sh themes/<папка>     # другая тема
#   ./scripts/07-theme.sh --reset            # убрать тему, вернуть встроенный вид
#   DRY_RUN=1 ./scripts/07-theme.sh          # ничего не меняет, только печатает
#
# Откат: ./scripts/07-theme.sh --reset (его же вызывает 99-restore.sh).
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 7: тема лаунчера ==="

# IP ТВ — из переменной окружения или из .tv-host.
if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
fi

PKG="com.gathin.tvhome"
URI="content://$PKG.theme"
TMP="/data/local/tmp/mitv-theme"
# Тема по умолчанию: своя у устройства (папка theme/ в папке устройства —
# так у приставки), иначе общая тема телевизора themes/glass-mitv.
if [[ -f "$DEVICE_DIR/theme/config.json" ]]; then
    DEFAULT_THEME="$DEVICE_DIR/theme"
else
    DEFAULT_THEME="$ROOT_DIR/themes/glass-mitv"
fi
ARG="${1:-$DEFAULT_THEME}"

RESET=0
if [[ "$ARG" == "--reset" ]]; then
    RESET=1
else
    # Полный путь к папке темы; если папки нет — пустая строка.
    THEME="$(cd "$ARG" 2>/dev/null && pwd)" || THEME=""
    if [[ -z "$THEME" || ! -f "$THEME/config.json" ]]; then
        err "Нет темы: в «$ARG» должен лежать config.json."
        exit 2
    fi
    # Битый JSON лаунчер просто проигнорирует, но лучше сказать сразу.
    if ! python3 -I -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8-sig"))' \
        "$THEME/config.json" 2>/dev/null; then
        err "$THEME/config.json — не корректный JSON. Исправь и запусти снова."
        exit 2
    fi
fi

ensure_adb_connected

DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
fi

# Список в переменную: `adb … | grep -q` под pipefail даёт ложное «нет»
# (SIGPIPE, см. 00-prepare.sh).
ALL_PKGS="$(adb_shell pm list packages | tr -d '\r')"
if ! grep -qxF "package:$PKG" <<< "$ALL_PKGS"; then
    err "Лаунчер $PKG не установлен — тему ставить некуда."
    err "Сначала $RUN_DIR/03-flauncher.sh с APK лаунчера CleanYourTV."
    exit 2
fi

# run <описание> <команда на ТВ> — с учётом DRY_RUN.
run() {
    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: $1"
    else
        adb_shell "$2" >/dev/null
    fi
}

# put_file <локальный файл> <имя в теме>
put_file() {
    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: записать $2 ← $1"
        return
    fi
    adb_cmd push "$1" "$TMP-$2" >/dev/null
    # content write печатает ошибку, но может вернуть 0 — проверяем текст.
    out="$(adb_shell "content write --uri $URI/$2 < $TMP-$2; rm -f $TMP-$2" 2>&1 | tr -d '\r')"
    if [[ -n "$out" ]]; then
        err "Не удалось записать $2: $out"
        exit 3
    fi
    log "Записано: $2"
}

if (( RESET )); then
    log "Убираю тему: лаунчер вернётся к встроенному виду."
    run "удалить config.json, wallpaper.jpg, wallpapers/*" \
        "content delete --uri $URI/config.json; content delete --uri $URI/wallpaper.jpg; content delete --uri $URI/wallpapers"
else
    log "Ставлю тему: $THEME"
    put_file "$THEME/config.json" config.json
    if [[ -f "$THEME/wallpaper.jpg" ]]; then
        put_file "$THEME/wallpaper.jpg" wallpaper.jpg
    else
        run "удалить wallpaper.jpg" "content delete --uri $URI/wallpaper.jpg"
    fi
fi

# Новая отметка версии: лаунчер увидит, что тему меняли, и примет её.
REV_FILE="$(mktemp)"
python3 -I -c 'import uuid; print(uuid.uuid4().hex, end="")' > "$REV_FILE"
put_file "$REV_FILE" theme_revision
rm -f "$REV_FILE"

# Перезапуск лаунчера, чтобы он перечитал тему.
run "am force-stop $PKG и нажать Home" \
    "am force-stop $PKG; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null"

if [[ "$DRY_RUN" != "1" ]]; then
    sleep 3
    ON_SCREEN="$(adb_shell dumpsys activity activities | tr -d '\r' \
        | grep -m1 -E 'mResumedActivity|ResumedActivity:' || true)"
    if [[ "$ON_SCREEN" == *"$PKG/"* ]]; then
        log "ОК: лаунчер перезапущен и на экране."
    else
        warn "После перезапуска на экране не лаунчер: ${ON_SCREEN:-<нет ответа>}"
        warn "Нажми Home на пульте. Если что-то не так — $RUN_DIR/07-theme.sh --reset"
    fi
fi

log "---"
log "Готово. Откат: $RUN_DIR/07-theme.sh --reset"
