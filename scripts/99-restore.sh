#!/usr/bin/env bash
# =============================================================================
# 99-restore.sh — откат всего, что сделали 02-debloat.sh и 03-flauncher.sh.
# -----------------------------------------------------------------------------
# Что делает:
#   1. Сравнивает, какие пакеты отключены на ТВ СЕЙЧАС, с самым первым
#      бэкапом (backup/<дата>/packages-disabled.txt — снят 01-backup.sh
#      до любого вмешательства). Всё, что отключено сейчас, но не было
#      отключено тогда, включает обратно: `pm enable <пакет>`.
#      Неважно, есть ли пакет в debloat.txt и закомментирован ли он там.
#   1½. Снимает запрет работы в фоне, поставленный 04-background.sh:
#      возвращает настройки из backup/appops-before.txt.
#   1¾. Возвращает отдельные компоненты, отключённые 05-components.sh:
#      состояния из backup/components-before.txt.
#   1⅞. Возвращает системные настройки, изменённые 06-settings.sh:
#      значения из backup/settings-before.txt.
#   2. (Опционально) удаляет новый лаунчер с ТВ (FLauncher / CleanYourTV).
#   2½. Убирает тему лаунчера CleanYourTV (07-theme.sh --reset), если он остаётся.
#   3. Возвращает домашний экран, записанный в бэкапе (home-activity.txt).
#
# Запуск:
#   ./scripts/99-restore.sh                          — только включить пакеты
#   REMOVE_LAUNCHER=1 ./scripts/99-restore.sh        — ещё и удалить новый лаунчер
#   DRY_RUN=1 ./scripts/99-restore.sh                — ничего не меняет, только
#                                                      печатает, что сделал бы
#   BACKUP=backup/<дата> ./scripts/99-restore.sh     — сравнивать с конкретным
#                                                      бэкапом, а не с первым
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Откат деблоата ==="

# IP ТВ — из переменной или из .tv-host.
if [[ -z "$TV_HOST" && -f "$HOST_FILE" ]]; then
    TV_HOST="$(cat "$HOST_FILE")"
fi

ensure_adb_connected

# Режим «сухой прогон»: показать команды, но ничего не выполнять.
DRY_RUN="${DRY_RUN:-0}"
if [[ "$DRY_RUN" == "1" ]]; then
    warn "DRY_RUN=1 — только печатаю команды, НИЧЕГО не меняю."
fi

# --- Выбор бэкапа -----------------------------------------------------------
# Берём САМЫЙ РАННИЙ бэкап: он снят до деблоата и показывает исходное
# состояние. Более поздний мог быть снят уже после отключений — тогда
# отключённое нами выглядело бы «родным» и не включилось бы обратно.
# Папки названы по дате (20261024-230046), поэтому сортировка по имени =
# сортировка по времени.
if [[ -n "${BACKUP:-}" ]]; then
    BK="$BACKUP"
else
    BK="$(find "$BACKUP_DIR" -mindepth 2 -maxdepth 2 -name packages-disabled.txt \
        -printf '%h\n' 2>/dev/null | sort | head -n 1)"
fi

if [[ -z "$BK" || ! -f "$BK/packages-disabled.txt" ]]; then
    err "Не найден бэкап с packages-disabled.txt (искал в $BACKUP_DIR)."
    err "Без него непонятно, какие пакеты были отключены до нас."
    err "Включить отдельный пакет можно вручную:"
    err "  adb -s $TV_HOST:$TV_PORT shell pm enable <имя пакета>"
    exit 2
fi
log "Сравниваю с бэкапом: $BK"

# --- Шаг 1. Включаем всё, что отключили после бэкапа ------------------------
cnt_enabled=0
cnt_errors=0

INSTALLED="$(adb_shell pm list packages | tr -d '\r' | sed 's|^package:||' | sort)"

# `pm list packages -d` — пакеты, отключённые сейчас (любым способом,
# в том числе нашим pm disable-user).
DISABLED_NOW="$(adb_shell pm list packages -d | tr -d '\r' | sed 's|^package:||' | sort)"
DISABLED_BEFORE="$(sed 's|^package:||' "$BK/packages-disabled.txt" | sort)"

# comm -23 оставляет строки, которые есть в первом списке и нет во втором:
# «отключено сейчас» минус «было отключено до нас».
TO_ENABLE="$(comm -23 <(echo "$DISABLED_NOW" | sed '/^$/d') \
                      <(echo "$DISABLED_BEFORE" | sed '/^$/d'))"

if [[ -z "$TO_ENABLE" ]]; then
    log "Нечего включать: отключены только те пакеты, что были отключены до нас."
fi

while IFS= read -r pkg; do
    [[ -z "$pkg" ]] && continue

    if [[ "$DRY_RUN" == "1" ]]; then
        log "DRY: pm enable $pkg"
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
done <<< "$TO_ENABLE"

log "---"
log "Включено:   $cnt_enabled"
log "Ошибок:     $cnt_errors"

# --- Шаг 1½. Снимаем запрет работы в фоне (то, что делал 04) ---------------
# 04-background.sh перед изменением записал исходную настройку каждого
# пакета в backup/appops-before.txt («<пакет> <режим>», режим обычно
# default). Возвращаем ровно её — даже если пакет уже убран из
# background.txt.
APPOPS_BEFORE="$BACKUP_DIR/appops-before.txt"
if [[ -f "$APPOPS_BEFORE" ]]; then
    log "---"
    log "Возвращаю настройки работы в фоне из $APPOPS_BEFORE"
    while read -r pkg mode; do
        [[ -z "$pkg" ]] && continue
        if ! echo "$INSTALLED" | grep -qxF "$pkg"; then
            log "SKIP: $pkg — не установлен"
            continue
        fi
        if [[ "$DRY_RUN" == "1" ]]; then
            log "DRY: cmd appops set $pkg RUN_ANY_IN_BACKGROUND $mode"
        elif out="$(adb_shell cmd appops set "$pkg" RUN_ANY_IN_BACKGROUND "$mode" 2>&1)"; then
            log "BG-ON: $pkg → $mode"
        else
            err "FAIL: $pkg — $out"
        fi
    done < "$APPOPS_BEFORE"
    # Записать на диск сразу — см. комментарий в 04-background.sh.
    if [[ "$DRY_RUN" != "1" ]]; then
        adb_shell cmd appops write-settings >/dev/null 2>&1 \
            || warn "Не удалось сразу записать настройки фона на диск."
    fi
fi

# --- Шаг 1⅞. Возвращаем системные настройки (то, что делал 06) -------------
# 06-settings.sh записал исходные значения в backup/settings-before.txt
# («<раздел> <ключ> <значение>»). «null» значит, что ключа не было —
# тогда удаляем его, и Android снова берёт значение по умолчанию.
SET_BEFORE="$BACKUP_DIR/settings-before.txt"
if [[ -f "$SET_BEFORE" ]]; then
    log "---"
    log "Возвращаю системные настройки из $SET_BEFORE"
    while read -r ns key value; do
        [[ -z "$ns" ]] && continue
        if [[ "$value" == "null" ]]; then
            cmd=(delete "$ns" "$key")
        else
            cmd=(put "$ns" "$key" "$value")
        fi
        if [[ "$DRY_RUN" == "1" ]]; then
            log "DRY: settings ${cmd[*]}"
        elif out="$(adb_shell settings "${cmd[@]}" 2>&1)"; then
            log "SET: $ns $key → $value"
        else
            err "FAIL: $ns $key — $out"
        fi
    done < "$SET_BEFORE"
fi

# --- Шаг 1¾. Возвращаем отдельные компоненты (то, что делал 05) ------------
# 05-components.sh записал исходное состояние каждой части приложения в
# backup/components-before.txt («<пакет>/<компонент> <состояние>»).
# default → pm default-state (ровно как было), enabled → pm enable,
# disabled → pm disable (было отключено ещё до нас — так и оставляем).
COMP_BEFORE="$BACKUP_DIR/components-before.txt"
if [[ -f "$COMP_BEFORE" ]]; then
    log "---"
    log "Возвращаю состояния компонентов из $COMP_BEFORE"
    while read -r comp state; do
        [[ -z "$comp" ]] && continue
        if ! echo "$INSTALLED" | grep -qxF "${comp%%/*}"; then
            log "SKIP: $comp — пакет не установлен"
            continue
        fi
        case "$state" in
            default)  cmd=(default-state --user 0 "$comp") ;;
            enabled)  cmd=(enable --user 0 "$comp") ;;
            disabled) cmd=(disable --user 0 "$comp") ;;
            *) err "FAIL: $comp — непонятное состояние «$state» в бэкапе"; continue ;;
        esac
        if [[ "$DRY_RUN" == "1" ]]; then
            log "DRY: pm ${cmd[*]}"
        elif out="$(adb_shell pm "${cmd[@]}" 2>&1)"; then
            log "COMP: $comp → $state"
        else
            err "FAIL: $comp — $out"
        fi
    done < "$COMP_BEFORE"
fi

# --- Шаг 2. Опционально: удаление нового лаунчера --------------------------
# По умолчанию лаунчер остаётся на ТВ — чтобы не терять его настройки.
# Если нужно полностью откатить ТВ в исходное состояние, запусти:
#   REMOVE_LAUNCHER=1 ./scripts/99-restore.sh
# (старое имя REMOVE_FLAUNCHER=1 тоже работает). Удаляются оба разрешённых
# лаунчера, если стоят: FLauncher и лаунчер CleanYourTV. Удаление идёт ДО
# шага 3, поэтому шаг 3 вернёт домашний экран из бэкапа.
REMOVE_LAUNCHER="${REMOVE_LAUNCHER:-${REMOVE_FLAUNCHER:-0}}"
if [[ "$REMOVE_LAUNCHER" == "1" ]]; then
    for L_PKG in me.efesser.flauncher com.gathin.tvhome; do
        if ! echo "$INSTALLED" | grep -qxF "$L_PKG"; then
            log "$L_PKG не установлен — пропускаю удаление."
            continue
        fi
        if [[ "$DRY_RUN" == "1" ]]; then
            log "DRY: adb uninstall $L_PKG"
            continue
        fi
        log "Удаляю лаунчер $L_PKG..."
        if out="$(adb_cmd uninstall "$L_PKG" 2>&1)"; then
            log "Лаунчер удалён ($out)"
        else
            err "Не удалось удалить $L_PKG: $out"
        fi
    done
fi

# --- Шаг 2½. Убираем тему лаунчера CleanYourTV (то, что делал 07) ---------
# Если лаунчер остаётся на ТВ — возвращаем ему встроенный вид через
# 07-theme.sh --reset. Если его только что удалили (REMOVE_LAUNCHER=1),
# тема ушла вместе с его данными.
if [[ "$REMOVE_LAUNCHER" != "1" ]] && echo "$INSTALLED" | grep -qxF "com.gathin.tvhome"; then
    log "---"
    log "Убираю тему лаунчера CleanYourTV (07-theme.sh --reset)"
    DRY_RUN="$DRY_RUN" TV_HOST="$TV_HOST" "$SCRIPTS_DIR/07-theme.sh" --reset \
        || warn "Сброс темы не удался — вручную: $RUN_DIR/07-theme.sh --reset"
fi

# --- Шаг 3. Возвращаем домашний экран, который был до нас -------------------
# Какой лаунчер был домом, записано в бэкапе (home-activity.txt), например
# com.google.android.tvlauncher/.MainActivity. Не угадываем «PatchWall»:
# на Mi TV 4S (2026-10-24) домом был Google tvlauncher, а не PatchWall.
# Бэкапы, снятые до исправления 01-backup.sh, вместо компонента содержат
# «No activity found» — такие пропускаем и берём самый ранний нормальный.
HOME_BEFORE=""
if [[ -n "${BACKUP:-}" ]]; then
    HOME_FILES="$BACKUP/home-activity.txt"
else
    HOME_FILES="$(find "$BACKUP_DIR" -mindepth 2 -maxdepth 2 -name home-activity.txt \
        2>/dev/null | sort)"
fi
while IFS= read -r f; do
    [[ -f "$f" ]] || continue
    # grep без совпадений вернёт ошибку — для «не нашли» это нормально.
    HOME_BEFORE="$(grep -E '^[A-Za-z0-9_.]+/' "$f" | tail -n 1 || true)"
    [[ -n "$HOME_BEFORE" ]] && break
done <<< "$HOME_FILES"

HOME_NOW="$(adb_shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | tail -n 1)"

log "---"
if [[ -z "$HOME_BEFORE" ]]; then
    warn "В бэкапах не записан домашний экран — его не трогаю."
    warn "Если после Home на пульте появится выбор — выбери нужный и 'Всегда'."
elif [[ "$HOME_NOW" == "$HOME_BEFORE" ]]; then
    log "Домашний экран уже исходный: $HOME_NOW"
elif [[ "$DRY_RUN" == "1" ]]; then
    log "DRY: cmd package set-home-activity $HOME_BEFORE   (сейчас: $HOME_NOW)"
elif out="$(adb_shell cmd package set-home-activity "$HOME_BEFORE" 2>&1)"; then
    log "Домашний экран возвращён: $HOME_BEFORE  ($out)"
else
    # Не роняем скрипт: пакеты уже включены, а дом можно выбрать пультом.
    warn "Не удалось назначить домашний экран: $out"
    warn "Нажми Home на пульте и выбери $HOME_BEFORE, затем 'Всегда'."
fi

log "---"
log "Готово. Нажми Home на пульте и проверь, что интерфейс вернулся."
log "Если что-то не так — на крайний случай сделай фабричный сброс через"
log "'Настройки → Настройки устройства → Сброс'."
