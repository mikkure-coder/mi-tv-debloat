#!/usr/bin/env bash
# =============================================================================
# scripts/lib/common.sh — общие функции для всех скриптов проекта.
# -----------------------------------------------------------------------------
# Этот файл НЕ запускается сам по себе. Остальные скрипты «подключают» его
# строкой `source "$(dirname "$0")/lib/common.sh"` и получают из него:
#   - настройки по умолчанию (TV_HOST, TV_PORT, пути для логов и бэкапов);
#   - функции log/warn/err — удобно писать сообщения с меткой времени;
#   - функцию require_cmd — проверить, что в системе установлен нужный бинарь;
#   - функцию ensure_adb_connected — безопасно подключиться к ТВ по сети.
# =============================================================================

# --- Строгий режим bash ------------------------------------------------------
# -e : скрипт падает на любой ошибке (а не делает вид, что всё ок);
# -u : падаем, если используется не заданная переменная (ловим опечатки);
# -o pipefail : падаем, если упала команда внутри «цепочки» через | .
set -euo pipefail

# --- Пути --------------------------------------------------------------------
# ROOT_DIR — корень репозитория. Вычисляется относительно расположения
# самого common.sh, чтобы скрипты работали из любого каталога.
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$LIB_DIR/.." && pwd)"
ROOT_DIR="$(cd "$SCRIPTS_DIR/.." && pwd)"

# DEVICE_DIR — папка устройства: в ней лежат его списки (packages/), бэкапы,
# логи и файл с IP (.tv-host). По умолчанию это корень репозитория, то есть
# телевизор Mi TV 4S. У приставки своя папка (mibox-s2/): её скрипты-обёртки
# задают DEVICE_DIR сами, поэтому списки и бэкапы двух устройств не смешаются.
# Общие файлы (apks/ с лаунчером, themes/) по-прежнему берутся из ROOT_DIR.
DEVICE_DIR="${DEVICE_DIR:-$ROOT_DIR}"
if [[ ! -d "$DEVICE_DIR/packages" ]]; then
    echo "ERROR: в папке устройства '$DEVICE_DIR' нет packages/ — это не папка устройства." >&2
    echo "       Запускай скрипты из scripts/ (телевизор) или из mibox-s2/scripts/ (приставка)." >&2
    exit 2
fi
PKG_DIR="$DEVICE_DIR/packages"
# Сам common.sh HOST_FILE не читает — его читают подключившие его скрипты.
# shellcheck disable=SC2034
HOST_FILE="$DEVICE_DIR/.tv-host"

# RUN_DIR — откуда запускать скрипты этого устройства (путь от корня
# репозитория). Его подставляют в подсказки вида «запусти …/99-restore.sh»,
# чтобы для приставки не советовать команды телевизора.
case "$DEVICE_DIR" in
    "$ROOT_DIR")   RUN_DIR="./scripts" ;;
    "$ROOT_DIR"/*) RUN_DIR="./${DEVICE_DIR#"$ROOT_DIR"/}/scripts" ;;
    *)             RUN_DIR="$DEVICE_DIR/scripts" ;;   # папка вне репозитория
esac

# Каталоги для логов и бэкапов. Создаются «на лету», если их нет.
LOG_DIR="$DEVICE_DIR/logs"
BACKUP_DIR="$DEVICE_DIR/backup"
mkdir -p "$LOG_DIR" "$BACKUP_DIR"

# --- Параметры подключения к ТВ ---------------------------------------------
# TV_HOST — IP телевизора в локальной сети. Можно задать переменной окружения:
#   TV_HOST=192.168.0.42 ./scripts/00-prepare.sh
# Если не задано — скрипт 00-prepare.sh спросит интерактивно.
TV_HOST="${TV_HOST:-}"
# Порт ADB-отладки по сети. На Android TV почти всегда 5555; переопределять
# нужно только если ты уверен, что в прошивке другой порт.
TV_PORT="${TV_PORT:-5555}"

# --- Логирование -------------------------------------------------------------
# Текущая метка времени (используется в именах файлов логов/бэкапов).
TS="$(date +%Y%m%d-%H%M%S)"

# Основной лог-файл сессии. Один файл на запуск любого скрипта.
# Внутри него каждый вызов log/warn/err добавляет строку с временем.
LOG_FILE="$LOG_DIR/${TS}-$(basename "${0:-session}" .sh).log"

# log  — информационное сообщение (пишем и в консоль, и в лог).
# warn — то же, но помечаем префиксом WARN.
# err  — ошибка. Печатаем в stderr, пишем в лог.
log()  { printf '[%s] %s\n'      "$(date +%H:%M:%S)" "$*" | tee -a "$LOG_FILE"; }
warn() { printf '[%s] WARN: %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG_FILE"; }
err()  { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG_FILE" >&2; }

# --- Проверка наличия бинарей -----------------------------------------------
# require_cmd adb — упадёт с понятным сообщением, если adb не установлен.
require_cmd() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        err "Не найден бинарь '$cmd'. Установи его и повтори запуск."
        err "  Для adb на Debian/Ubuntu: sudo apt install android-tools-adb"
        err "  Для adb на Fedora:       sudo dnf install android-tools"
        err "  Для adb на macOS:        brew install android-platform-tools"
        exit 2
    fi
}

# --- Подключение к ТВ по сети ------------------------------------------------
# Делает `adb connect <host>:<port>` и проверяет, что устройство реально
# появилось в списке `adb devices` в состоянии `device` (не `offline`,
# не `unauthorized`).
ensure_adb_connected() {
    require_cmd adb

    if [[ -z "$TV_HOST" ]]; then
        err "Не задан IP телевизора. Запусти $RUN_DIR/00-prepare.sh или"
        err "передай переменную: TV_HOST=192.168.X.Y $0"
        exit 2
    fi

    log "Пробую подключиться к $TV_HOST:$TV_PORT ..."
    # Выход adb connect не всегда отражает реальный статус,
    # поэтому ниже дополнительно проверяем `adb devices`.
    adb connect "$TV_HOST:$TV_PORT" >/dev/null || true

    # Проверяем, что устройство реально в состоянии `device`. Ждём до 10 с:
    # сразу после `adb connect` устройство может ещё не появиться в списке
    # (на приставке 2026-10-25 через 1 с было «missing», хотя оно ждало
    # подтверждения). Если ждёт подтверждения на экране — даём человеку
    # до 60 с нажать OK.
    local state="missing" i
    for ((i = 0; i < 70; i++)); do
        state="$(adb -s "$TV_HOST:$TV_PORT" get-state 2>/dev/null || echo "missing")"
        [[ "$state" == "device" ]] && break
        if [[ "$state" == "unauthorized" ]]; then
            (( i == 10 )) && warn "Подтверди запрос «Разрешить отладку?» на экране (жду до минуты)..."
        elif (( i >= 10 )); then
            break
        fi
        sleep 1
    done

    case "$state" in
        device)
            log "ОК: подключение к $TV_HOST:$TV_PORT установлено."
            ;;
        unauthorized)
            err "ТВ ответил, но запрос ADB не подтверждён на экране."
            err "Включи 'Отладка по сети' в режиме разработчика и подтверди"
            err "запрос на телевизоре (чекбокс 'Всегда разрешать с этого компьютера')."
            exit 3
            ;;
        offline|missing|*)
            err "Не удалось подключиться (состояние: '$state')."
            err "Проверь, что:"
            err "  - ТВ и этот компьютер в одной Wi-Fi/LAN сети;"
            err "  - в настройках ТВ включена 'Отладка по сети';"
            err "  - IP '$TV_HOST' верный (посмотри в Настройки → Сеть)."
            exit 3
            ;;
    esac
}

# Обёртка над `adb shell`, которая всегда адресует команду конкретному ТВ.
# Это защищает от ситуации, когда к компьютеру подключён и телефон, и ТВ:
# без явного `-s` adb выдаст ошибку «more than one device».
#
# `</dev/null` — обязательно. `adb shell` пересылает на ТВ всё, что приходит
# ему на стандартный ввод. Внутри цикла `while read … done < debloat.txt`
# первый же вызов «съедал» остаток файла, и цикл обрывался после первого
# пакета (поймано на живом ТВ 2026-10-24). Пустой ввод это исключает.
adb_shell() {
    adb -s "$TV_HOST:$TV_PORT" shell "$@" </dev/null
}

# Аналогично для `adb install`, `adb pull` и т.п.
adb_cmd() {
    # </dev/null — по той же причине, что и в adb_shell.
    adb -s "$TV_HOST:$TV_PORT" "$@" </dev/null
}

# --- Стоп-лист -----------------------------------------------------------------
# Файл со списком пакетов, которые нельзя трогать никаким скриптом проекта.
KEEP_FILE="$PKG_DIR/keep.txt"

# list_names <файл> — имена пакетов из файла: без комментариев (всё после «#»)
# и пустых строк, по одному на строку.
list_names() {
    sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$1" | sed '/^$/d'
}

# --- Жёсткий запрет: ядро Android и стоп-лист -------------------------------
# check_stoplist <файл списка> проверяет ВЕСЬ список ДО подключения к ТВ:
# если в нём есть хоть один запрещённый пакет, скрипт выходит, ничего не
# сделав. Обойти запрет переменной нельзя — нужно убрать строку из списка.
# Используется в 02 (отключение) и 04 (запрет работы в фоне).
# Причина: на Mi TV Stick (CleanYourTV, issue #884) агент отключил части
# Android — permissioncontroller, packageinstaller, ext.services и др. —
# и приставка перестала загружаться навсегда: ADB по сети стартует слишком
# поздно, чтобы что-то вернуть.
# Запрещено:
#   - всё из packages/keep.txt;
#   - встроенный список ниже — на случай, если keep.txt испортят или удалят;
#   - пакеты по шаблону: оверлеи модулей Android и контент-провайдеры
#     (кроме календаря, контактов и словаря — их отключение безопасно).
is_core_pkg() {
    case "$1" in
        android|com.android.systemui|com.android.shell|com.android.phone|\
        com.android.settings|com.android.tv.settings|\
        com.google.android.permissioncontroller|com.android.permissioncontroller|\
        com.google.android.packageinstaller|com.android.packageinstaller|\
        com.google.android.ext.services|com.google.android.ext.shared|\
        com.google.android.modulemetadata|\
        com.google.android.tungsten.setupwraith|\
        com.google.android.overlay.modules.*)
            return 0 ;;
        com.android.providers.calendar|com.android.providers.contacts|\
        com.android.providers.userdictionary|com.android.providers.blockednumber)
            return 1 ;;
        com.android.providers.*)
            return 0 ;;
    esac
    return 1
}

# --- Узкое исключение: мастер настройки на приставке -------------------------
# На приставке Xiaomi TV Box S 2 (Google TV) новый лаунчер становится домом,
# только если отключить мастер первой настройки setupwraith (CleanYourTV и
# наша проверка 2026-10-25). Решение владельца 2026-10-25: разрешить это
# ТОЛЬКО для устройства, в папке которого есть packages/core-exceptions.txt
# с этим именем. У телевизора такого файла нет — для него запрет прежний.
# Исключить можно только имена из CORE_EXCEPTIONABLE; любое другое имя в
# core-exceptions.txt игнорируется. Отключение мастера дополнительно
# охраняет 02: сеть Wi-Fi не должна принадлежать ему, и должен быть новый дом.
CORE_EXCEPTIONABLE="com.google.android.tungsten.setupwraith"
CORE_EXCEPTIONS_FILE="$PKG_DIR/core-exceptions.txt"
is_core_exception() {
    [[ -f "$CORE_EXCEPTIONS_FILE" ]] || return 1
    grep -qxF "$1" <<< "$CORE_EXCEPTIONABLE" || return 1
    list_names "$CORE_EXCEPTIONS_FILE" | grep -qxF "$1"
}

# Второй аргумент (необязательный) — имя списка для сообщений; нужен, когда
# список передан как <(…), у которого «имя файла» — /dev/fd/63.
check_stoplist() {
    local list="$1" list_name pkg blocked=0
    list_name="${2:-$(basename "$list")}"
    local KEEP_NAMES=""
    if [[ -f "$KEEP_FILE" ]]; then
        KEEP_NAMES="$(list_names "$KEEP_FILE")"
    else
        warn "Нет $KEEP_FILE — проверяю только по встроенному списку ядра."
    fi

    while IFS= read -r pkg; do
        [[ -z "$pkg" ]] && continue
        if is_core_exception "$pkg" && ! echo "$KEEP_NAMES" | grep -qxF "$pkg"; then
            warn "Исключение для этого устройства: $pkg разрешён (core-exceptions.txt)."
            continue
        fi
        if is_core_pkg "$pkg" || echo "$KEEP_NAMES" | grep -qxF "$pkg"; then
            err "ЗАПРЕЩЕНО: $pkg есть в $list_name, но это ядро Android или стоп-лист."
            blocked=$((blocked + 1))
        fi
    done < <(list_names "$list")

    if (( blocked > 0 )); then
        err "Ничего не делаю. Убери эти строки из ${PKG_DIR#"$ROOT_DIR"/}/$list_name"
        err "(или закомментируй «#») и запусти скрипт снова."
        err "Почему так строго: отключение таких пакетов может оставить ТВ"
        err "не загружающимся — и вернуть его можно только сбросом или в сервисе."
        exit 2
    fi
    log "Проверка стоп-листа: запрещённых пакетов в $list_name нет."
}
