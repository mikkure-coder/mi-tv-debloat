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

# Каталоги для логов и бэкапов. Создаются «на лету», если их нет.
LOG_DIR="$ROOT_DIR/logs"
BACKUP_DIR="$ROOT_DIR/backup"
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
        err "Не задан IP телевизора. Запусти scripts/00-prepare.sh или"
        err "передай переменную: TV_HOST=192.168.X.Y $0"
        exit 2
    fi

    log "Пробую подключиться к $TV_HOST:$TV_PORT ..."
    # Выход adb connect не всегда отражает реальный статус,
    # поэтому ниже дополнительно проверяем `adb devices`.
    adb connect "$TV_HOST:$TV_PORT" >/dev/null || true

    # Небольшая пауза: ТВ иногда отвечает с задержкой.
    sleep 1

    # Проверяем, что устройство реально в состоянии `device`.
    local state
    state="$(adb -s "$TV_HOST:$TV_PORT" get-state 2>/dev/null || echo "missing")"

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
KEEP_FILE="$ROOT_DIR/packages/keep.txt"

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

check_stoplist() {
    local list="$1" list_name pkg blocked=0
    list_name="$(basename "$list")"
    local KEEP_NAMES=""
    if [[ -f "$KEEP_FILE" ]]; then
        KEEP_NAMES="$(list_names "$KEEP_FILE")"
    else
        warn "Нет $KEEP_FILE — проверяю только по встроенному списку ядра."
    fi

    while IFS= read -r pkg; do
        [[ -z "$pkg" ]] && continue
        if is_core_pkg "$pkg" || echo "$KEEP_NAMES" | grep -qxF "$pkg"; then
            err "ЗАПРЕЩЕНО: $pkg есть в $list_name, но это ядро Android или стоп-лист."
            blocked=$((blocked + 1))
        fi
    done < <(list_names "$list")

    if (( blocked > 0 )); then
        err "Ничего не делаю. Убери эти строки из packages/$list_name"
        err "(или закомментируй «#») и запусти скрипт снова."
        err "Почему так строго: отключение таких пакетов может оставить ТВ"
        err "не загружающимся — и вернуть его можно только сбросом или в сервисе."
        exit 2
    fi
    log "Проверка стоп-листа: запрещённых пакетов в $list_name нет."
}
