#!/usr/bin/env bash
# =============================================================================
# 08-vitrina.sh — ставит наш лаунчер «Витрина» и передаёт ему настройки.
# -----------------------------------------------------------------------------
# «Витрина» — свой лаунчер проекта (исходники в launcher/, решение владельца
# 2026-10-25): светлый экран как в Apple TV, часы, погода и курсы. В отличие
# от CleanYourTV, ему нужен интернет — только для api.met.no (погода),
# cbr-xml-daily.ru и api.coingecko.com (курсы).
#
# Что делает:
#   1. Ставит APK из launcher/app/build/outputs/apk/release/ (adb install -r).
#   2. Передаёт настройки: ряд приложений и город. Источник необязательный,
#      лежит в папке устройства и в git НЕ попадает (см. .gitignore):
#        vitrina.json — {"dock": [...], "city": "Москва", "lat": 55.75, "lon": 37.62}
#                       (пример — launcher/vitrina.example.json).
#   3. Только с SET_HOME=1 — делает «Витрину» домашним экраном и проверяет
#      нажатием Home, что открылась она. Прежний лаунчер остаётся установленным.
#
# Запуск:
#   (cd launcher && ./gradlew assembleRelease)      # один раз собрать
#   ./mibox-s2/scripts/08-vitrina.sh                # приставка
#   SET_HOME=1 ./mibox-s2/scripts/08-vitrina.sh     # и сделать домом
#   DRY_RUN=1 ./mibox-s2/scripts/08-vitrina.sh      # только показать
#
# Откат: REMOVE_LAUNCHER=1 99-restore.sh удалит «Витрину» и вернёт дом из
# бэкапа. Или только дом: cmd package set-home-activity <прежний лаунчер>.
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 8: лаунчер «Витрина» ==="

if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
fi

PKG="org.mitvdebloat.vitrina"
HOME_ACT="$PKG/.MainActivity"
APK="$ROOT_DIR/launcher/app/build/outputs/apk/release/app-release.apk"
CFG="$DEVICE_DIR/vitrina.json"
DRY_RUN="${DRY_RUN:-0}"
SET_HOME="${SET_HOME:-0}"

if [[ ! -f "$APK" ]]; then
    err "Нет собранного APK: $APK"
    err "Собери его: (cd launcher && ./gradlew assembleRelease)"
    err "Нужны Java 17 и Android SDK в ~/Android/Sdk (см. launcher/README.md)."
    exit 2
fi
require_cmd python3

# Настройки собираем в JSON и кодируем в base64: так кавычки и кириллица
# доходят до приставки через adb shell без порчи.
if [[ -f "$CFG" ]] && ! python3 -I -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$CFG" 2>/dev/null; then
    err "$CFG — не корректный JSON. Сверь с launcher/vitrina.example.json."
    exit 2
fi
B64="$(python3 -I - "$CFG" <<'PY'
import base64, json, os, sys
cfg = sys.argv[1]
d = json.load(open(cfg, encoding="utf-8")) if os.path.exists(cfg) else {}
print(base64.b64encode(json.dumps(d, ensure_ascii=False).encode()).decode())
PY
)"
if [[ -f "$CFG" ]]; then log "Настройки: $CFG"; else log "Своих настроек нет ($CFG) — у лаунчера останутся прежние."; fi

ensure_adb_connected

if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
    log "DRY: adb install -r $APK"
    log "DRY: am broadcast -n $PKG/.ConfigReceiver -a $PKG.CONFIG --es b64 <настройки>"
    [[ "$SET_HOME" == "1" ]] && log "DRY: cmd package set-home-activity $HOME_ACT и проверка кнопкой Home"
    exit 0
fi

log "Ставлю $APK ..."
if ! out="$(adb_cmd install -r "$APK" 2>&1)"; then
    err "Установка не удалась: $out"
    exit 3
fi
log "Установлено: $(echo "$out" | tail -n 1)"

# Приёмник настроек закрыт правом DUMP: слать их может только adb shell.
res="$(adb_shell am broadcast -n "$PKG/.ConfigReceiver" -a "$PKG.CONFIG" --es b64 "$B64" | tr -d '\r' | grep -o 'data="[^"]*"' || true)"
if [[ "$res" == 'data="OK"' ]]; then
    log "Настройки приняты."
else
    warn "Лаунчер не подтвердил настройки (${res:-нет ответа}). Запусти скрипт ещё раз."
fi

if [[ "$SET_HOME" == "1" ]]; then
    out="$(adb_shell cmd package set-home-activity "$HOME_ACT" 2>&1 | tr -d '\r')"
    log "set-home-activity: $out"
    adb_shell input keyevent KEYCODE_HOME
    sleep 3
    ON_SCREEN="$(adb_shell dumpsys activity activities | tr -d '\r' | grep -m1 -E 'mResumedActivity|ResumedActivity:' || true)"
    if [[ "$ON_SCREEN" == *"$PKG/"* ]]; then
        log "ОК: по кнопке Home открывается «Витрина»."
    else
        warn "По Home открылась НЕ «Витрина»: ${ON_SCREEN:-нет ответа}."
        warn "Прежний лаунчер на месте. Нажми Home на пульте и выбери «Витрина» → «Всегда»."
    fi
else
    adb_shell am start -n "$HOME_ACT" >/dev/null
    log "«Витрина» открыта, но домашним экраном не назначена (это SET_HOME=1)."
fi
log "Откат: REMOVE_LAUNCHER=1 $RUN_DIR/99-restore.sh"
