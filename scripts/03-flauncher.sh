#!/usr/bin/env bash
# =============================================================================
# 03-flauncher.sh — установка нового лаунчера и назначение его домашним экраном.
# -----------------------------------------------------------------------------
# Разрешены два лаунчера (см. CLAUDE.md, раздел 2):
#   - FLauncher (me.efesser.flauncher) — готовый APK из F-Droid,
#     https://gitlab.com/flauncher/flauncher;
#   - лаунчер CleanYourTV (com.gathin.tvhome) — в стиле Apple TV, только
#     собранный на ноутбуке из исходников https://github.com/Gathin23/cleanyourtv.
#     Готовый APK кладётся в apks/. Их установщик НЕ используем.
# Штатный Google-лаунчер скрипт НЕ отключает: он остаётся запасным домом.
#
# Что делает этот скрипт:
#   1. Ищет APK лаунчера в apks/ (локальная копия) или в корне репозитория.
#   2. Если локального APK нет — пытается скачать FLauncher из F-Droid.
#      Если и это не удаётся (нет интернета / F-Droid недоступен) —
#      выводит понятную инструкцию, где скачать вручную.
#   3. Устанавливает APK на ТВ через `adb install -r`.
#   4. Пытается назначить его домашним экраном через
#      `cmd package set-home-activity`.
#   5. Если автоматом не вышло — выводит инструкцию: нажми Home на пульте
#      и выбери новый лаунчер в системном диалоге.
#
# Запуск:
#   ./scripts/03-flauncher.sh
#   DRY_RUN=1 ./scripts/03-flauncher.sh   # ничего не качает и не меняет на ТВ,
#                                         # только печатает, что сделал бы.
#
# Откат: ./scripts/99-restore.sh (домашний экран из бэкапа) и
#        REMOVE_LAUNCHER=1 ./scripts/99-restore.sh (удалить новый лаунчер).
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 3: установка лаунчера ==="

# IP ТВ — из переменной или .tv-host
if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
fi

ensure_adb_connected

# Режим «сухой прогон»: показать команды, но ничего не выполнять.
DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не качаю и не меняю на ТВ."
fi

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

# newest_apk <каталог> — самый свежий по времени изменения *.apk в каталоге
# (find печатает «время путь», sort -rn ставит новейший первым).
newest_apk() {
    find "$1" -maxdepth 1 -name '*.apk' -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | head -n 1 | cut -d' ' -f2-
}

# Берём последний APK в apks/.
if [[ -n "$(newest_apk "$APK_DIR")" ]]; then
    APK="$(newest_apk "$APK_DIR")"
    log "Нашёл локальный APK: $APK"
elif [[ -n "$(newest_apk "$ROOT_DIR")" ]]; then
    APK="$(newest_apk "$ROOT_DIR")"
    log "Нашёл APK в корне репозитория: $APK"
fi

# --- Шаг 2. Если APK не нашли — пробуем качнуть ------------------------------
if [[ -z "$APK" && "$DRY_RUN" == "1" ]]; then
    log "DRY: локального APK нет — скачал бы последний FLauncher с f-droid.org"
    log "DRY: adb install -r <скачанный APK>"
    log "DRY: cmd package set-home-activity <компонент лаунчера>"
    log "DRY: input keyevent KEYCODE_HOME и проверка, что открылся новый лаунчер"
    exit 0
fi

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
    err "APK лаунчера не найден и не скачан. Что делать:"
    err "  Лаунчер CleanYourTV: собери его из исходников (см. README) и положи"
    err "    app-release.apk в каталог: $APK_DIR"
    err "  FLauncher:"
    err "  1. Открой на обычном компьютере страницу:"
    err "       https://f-droid.org/en/packages/me.efesser.flauncher/"
    err "     либо релизы: https://gitlab.com/flauncher/flauncher/-/releases"
    err "  2. Скачай последний APK."
    err "  3. Положи его в каталог: $APK_DIR"
    err "  4. Снова запусти: $RUN_DIR/03-flauncher.sh"
    exit 2
fi

# --- Шаг 4. Устанавливаем APK на ТВ ------------------------------------------
# Запомним список пакетов ДО установки — чтобы потом точно знать, что добавилось.
BEFORE="$(adb_shell pm list packages | tr -d '\r' | sort)"

if [[ "$DRY_RUN" == "1" ]]; then
    log "DRY: adb install -r $APK"
    log "DRY: cmd package set-home-activity <компонент из установленного APK>"
    log "DRY: input keyevent KEYCODE_HOME и проверка, что открылся новый лаунчер"
    exit 0
fi

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
    NEW_PKG="$(echo "$AFTER" | grep -E '^package:(me\.efesser\.flauncher|com\.gathin\.tvhome)$' | head -n 1 | sed 's|^package:||')"
fi

if [[ -z "$NEW_PKG" ]]; then
    warn "Не могу определить имя установленного пакета лаунчера."
    warn "Это не критично: установка прошла, home-activity назначь вручную"
    warn "через меню ТВ (Настройки → Приложения → Домашний экран)."
    exit 0
fi

log "Пакет лаунчера: $NEW_PKG"

# --- Шаг 5. Пытаемся назначить его home-activity -----------------------------
# Для set-home-activity нужен компонент вида "<pkg>/<активность>".
# Запрашиваем у package manager, какую активность пакет регистрирует как HOME.
HOME_COMPONENT="$(adb_shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME "$NEW_PKG" 2>/dev/null \
    | tr -d '\r' | tail -n 1)"

log "Компонент home-activity от $NEW_PKG: ${HOME_COMPONENT:-<не определён>}"

if [[ -n "$HOME_COMPONENT" && "$HOME_COMPONENT" == *"/"* ]]; then
    if out="$(adb_shell cmd package set-home-activity "$HOME_COMPONENT" 2>&1)"; then
        log "set-home-activity ответил: $out"
    else
        warn "set-home-activity не сработал: $out"
        warn "Это нормально на некоторых прошивках Mi TV."
        warn "Переходим к ручному назначению ↓"
        HOME_COMPONENT=""
    fi
fi

# --- Шаг 5½. Проверяем на деле, а не по ответу «Success» ---------------------
# По опыту CleanYourTV (TROUBLESHOOTING, Xiaomi MOOR2 и др.) set-home-activity
# может напечатать «Success» и ничего не поменять. Поэтому жмём Home так же,
# как пультом (`input keyevent KEYCODE_HOME`), ждём и спрашиваем у системы,
# какое приложение РЕАЛЬНО на экране (`dumpsys activity` → mResumedActivity).
# Если там не новый лаунчер — штатный лаунчер НЕ отключать: он единственный дом.
if [[ -n "$HOME_COMPONENT" ]]; then
    adb_shell input keyevent KEYCODE_HOME
    sleep 3
    ON_SCREEN="$(adb_shell dumpsys activity activities | tr -d '\r' \
        | grep -m1 'mResumedActivity' || true)"
    log "На экране после Home: ${ON_SCREEN:-<система не ответила>}"
    if [[ "$ON_SCREEN" == *"$NEW_PKG/"* ]]; then
        log "ОК: по кнопке Home открывается $NEW_PKG — проверено на деле."
    else
        warn "set-home-activity сказал «успех», но по Home открылся НЕ $NEW_PKG."
        warn "Штатный лаунчер пока НЕ отключай вслепую. Если у него приоритет HOME"
        warn "выше (так на Mi TV 4S), сначала открой новый лаунчер из списка"
        warn "приложений, убедись, что работает, и отключи штатный через 02 —"
        warn "см. README, шаг 3."
        warn "Переходим к ручному назначению ↓"
        HOME_COMPONENT=""
    fi
fi

# --- Шаг 6. Ручной fallback --------------------------------------------------
if [[ -z "$HOME_COMPONENT" ]]; then
    log "---"
    log "Назначь новый лаунчер домашним экраном вручную:"
    log "  1. Нажми Home на пульте телевизора."
    log "  2. ТВ покажет диалог выбора лаунчера."
    log "  3. Выбери его и 'Всегда' (или 'Запомнить выбор')."
    log ""
    log "Если диалог не появился:"
    log "  Настройки → Приложения → По умолчанию → Домашний экран."
fi

log "---"
log "Готово. Проверка: нажми Home на пульте."
log "Перед любым следующим шагом перезагрузи ТВ и убедись, что после"
log "загрузки по Home открывается тот лаунчер, который ты ждёшь."
