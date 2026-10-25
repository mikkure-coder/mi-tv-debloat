#!/usr/bin/env bash
# =============================================================================
# 02-debloat.sh — отключает (НЕ удаляет) пакеты из packages/debloat.txt.
# -----------------------------------------------------------------------------
# Что происходит:
#   1. Файл packages/debloat.txt читается построчно.
#   2. Пустые строки и строки, начинающиеся с «#», пропускаются.
#   2½. ДО подключения к ТВ весь список сверяется со стоп-листом
#      (packages/keep.txt + встроенный список ядра Android). Нашёлся хоть
#      один запрещённый пакет — скрипт не отключает НИЧЕГО и выходит.
#   3. Для каждого пакета:
#        a. проверяем, что он реально установлен на ТВ;
#        b. если это домашний экран (по ответу ТВ) или PatchWall
#           (com.mitv.tvhome / com.mitv.tvhome.atv) — делаем
#           дополнительную проверку: останется ли на устройстве другой home
#           (FLauncher или штатный Android TV launcher). Если нет —
#           ОТКАЗЫВАЕМСЯ отключать, чтобы не оставить ТВ без домашнего экрана;
#        c. выполняем `pm disable-user --user 0 <пакет>`.
#   4. В конце печатаем краткий отчёт: сколько отключено / пропущено / ошибок.
#
# Безопасность:
#   - pm disable-user НЕ удаляет пакет, только «замораживает».
#     Любой пакет можно вернуть одной командой `pm enable <пакет>`.
#   - Фабричный сброс ТВ полностью восстанавливает состояние по умолчанию.
#
# Запуск:
#   ./scripts/02-debloat.sh
#   DRY_RUN=1 ./scripts/02-debloat.sh   # ничего не меняет, только печатает,
#                                       # что собирается сделать.
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 2: деблоат ==="

# IP ТВ — из переменной окружения или из .tv-host.
if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
fi

# Файл со списком «мусора». Это тот самый, который редактирует пользователь.
DEBLOAT_FILE="$PKG_DIR/debloat.txt"

if [[ ! -f "$DEBLOAT_FILE" ]]; then
    err "Нет файла $DEBLOAT_FILE — нечего отключать."
    exit 2
fi

# Жёсткий запрет: весь debloat.txt сверяется со стоп-листом (keep.txt +
# ядро Android) ДО подключения к ТВ. Совпадение — выход без единого
# отключения. Сама проверка и её обоснование — в lib/common.sh.
check_stoplist "$DEBLOAT_FILE"

ensure_adb_connected

# Режим «сухой прогон»: показать команды, но ничего не выполнять.
# Переменная DRY_RUN задаётся при запуске: DRY_RUN=1 ./02-debloat.sh
DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
fi

# Снимем один раз актуальный список установленных пакетов, чтобы не дёргать
# adb на каждом пакете из debloat.txt. Это и быстрее, и даёт нам срез
# ровно того, что сейчас на ТВ.
INSTALLED="$(adb_shell pm list packages | tr -d '\r' | sed 's|^package:||' | sort)"

# Какие пакеты СЕЙЧАС умеют быть домашним экраном. Спрашиваем у самого ТВ,
# а не угадываем по имени: на Mi TV 4S (2026-10-24) домом оказался Google
# tvlauncher, а PatchWall назывался com.mitv.tvhome.atv и домом не был.
# Отключённые пакеты система сюда не включает — значит, «запасной» лаунчер,
# который уже выключен, за запасной не посчитается.
# Настоящим домашним экраном НЕ считаются две аварийные заглушки без иконок:
#   - FallbackHome из com.android.tv.settings;
#   - RecoveryActivity мастера настройки com.google.android.tungsten.setupwraith.
#     На приставке Xiaomi TV Box S 2 (Google TV) она числится домом с
#     приоритетом 1 (CleanYourTV, examples/xiaomi-mi-box-s-2nd-gen). Без этого
#     исключения скрипт счёл бы её «запасным домом» и дал отключить Google TV
#     ещё до установки нового лаунчера.
HOME_PKGS="$(adb_shell cmd package query-activities --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | grep -oE '^[[:space:]]*[A-Za-z0-9_.]+/' | tr -d ' /' \
    | grep -vxF -e 'com.android.tv.settings' -e 'com.google.android.tungsten.setupwraith' \
    | sort -u)"
log "Домашние экраны на ТВ: $(echo "$HOME_PKGS" | tr '\n' ' ')"

# Счётчики. В конце напечатаем сводку.
cnt_disabled=0   # сколько успешно отключили
cnt_skipped=0    # сколько пропустили (не установлены или уже выключены)
cnt_errors=0     # сколько завершилось ошибкой

# --- Чтение debloat.txt построчно ------------------------------------------
# IFS= и -r — безопасные настройки read, чтобы не было магии с пробелами
# и обратными слэшами.
while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    # Срезаем всё, что идёт после «#» в строке (встроенные комментарии).
    line="${raw_line%%#*}"
    # Удаляем пробелы по краям.
    line="$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

    # Пустую строку — пропускаем молча.
    [[ -z "$line" ]] && continue

    pkg="$line"

    # --- Шаг A. Пакет реально установлен? -----------------------------------
    if ! echo "$INSTALLED" | grep -qxF "$pkg"; then
        log "SKIP: $pkg — не установлен на этом ТВ"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi

    # --- Шаг B. Особый случай: домашний экран (launcher) --------------------
    # Если пакет — домашний экран (по ответу ТВ) или PatchWall (по имени),
    # удостоверимся, что после отключения у ТВ останется другой home-screen.
    # Иначе получится «кирпич» без домашнего экрана
    # (ресетится только фабричным сбросом).
    if echo "$HOME_PKGS" | grep -qxF "$pkg" || [[ "$pkg" =~ ^com\.mitv\.tvhome(\.atv)?$ ]]; then
        if ! echo "$HOME_PKGS" | grep -vxF "$pkg" | grep -q .; then
            warn "SKIP: $pkg — после отключения на ТВ не останется домашнего экрана"
            warn "       (ни штатного Android TV, ни FLauncher)."
            warn "       Сначала запусти 03-flauncher.sh, потом перезапусти этот скрипт."
            cnt_skipped=$((cnt_skipped + 1))
            continue
        fi
    fi

    # --- Шаг B½. Мастер настройки (только по исключению устройства) --------
    # До него дойдёт только на устройстве с core-exceptions.txt (см.
    # lib/common.sh). Две проверки, обе на живом устройстве:
    #   1) должен быть настоящий новый дом — не Google-лаунчер. Иначе после
    #      отключения мастера дома не останется вовсе, кроме FallbackHome;
    #   2) текущая сеть Wi-Fi не должна принадлежать мастеру: Android удаляет
    #      сети отключённого приложения, а с Wi-Fi пропадёт и ADB. Если сеть
    #      его — забудь её в Настройках и подключись заново, тогда владельцем
    #      станет система.
    if [[ "$pkg" == "com.google.android.tungsten.setupwraith" ]]; then
        NEW_HOMES="$(echo "$HOME_PKGS" | grep -vxE 'com\.google\.android\.(apps\.tv\.launcherx|tvlauncher|leanbacklauncher)' || true)"
        if [[ -z "$NEW_HOMES" ]]; then
            warn "SKIP: $pkg — нет нового лаунчера, который станет домом."
            warn "       Сначала $RUN_DIR/03-flauncher.sh."
            cnt_skipped=$((cnt_skipped + 1))
            continue
        fi
        WIFI_NOW="$(adb_shell dumpsys wifi | tr -d '\r' | grep 'mostRecentlyConnected=true' || true)"
        if [[ -z "$WIFI_NOW" || "$WIFI_NOW" == *"cname=$pkg "* ]]; then
            warn "SKIP: $pkg — текущая сеть Wi-Fi принадлежит мастеру (или это не удалось проверить)."
            warn "       Настройки → Сеть и Интернет → твоя сеть → Забыть сеть, потом подключись заново"
            warn "       и запусти этот скрипт снова."
            cnt_skipped=$((cnt_skipped + 1))
            continue
        fi
        log "Мастер настройки: новый дом есть ($(echo "$NEW_HOMES" | tr '\n' ' ')), сеть Wi-Fi не его."
    fi

    # --- Шаг C. Собственно отключение ---------------------------------------
    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: pm disable-user --user 0 $pkg"
        cnt_skipped=$((cnt_skipped + 1))
        continue
    fi

    # Выполняем команду. Вывод adb перехватываем, чтобы в случае ошибки
    # показать её целиком — и чтобы при успехе лог оставался коротким.
    if out="$(adb_shell pm disable-user --user 0 "$pkg" 2>&1)"; then
        log "OFF: $pkg  ($out)"
        cnt_disabled=$((cnt_disabled + 1))
    else
        err "FAIL: $pkg — $out"
        cnt_errors=$((cnt_errors + 1))
    fi
done < "$DEBLOAT_FILE"

# --- Итоговая сводка -------------------------------------------------------
log "---"
log "Итого:"
log "  отключено:  $cnt_disabled"
log "  пропущено:  $cnt_skipped"
log "  ошибок:     $cnt_errors"

# Проверим, какой сейчас launcher: вдруг мы только что отключили PatchWall,
# но Android ещё не переключил home — тогда следующий Home на пульте
# пройдёт через системный диалог «выберите домашний экран».
log "---"
log "Текущий домашний экран:"
adb_shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tee -a "$LOG_FILE"

log "---"
log "Готово. Проверь ТВ: нажми Home на пульте, перезагрузи через 'Настройки → Перезагрузка'."
log "Если что-то отвалилось — запусти $RUN_DIR/99-restore.sh"
