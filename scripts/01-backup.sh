#!/usr/bin/env bash
# =============================================================================
# 01-backup.sh — снимаем бэкап текущего состояния пакетов на ТВ.
# -----------------------------------------------------------------------------
# Зачем это нужно:
#   Отключая пакеты, мы точно знаем, какие ИЗНАЧАЛЬНО были установлены и
#   каков их статус (включён/отключён). Если что-то сломается — у нас есть
#   «фотография» исходной системы, по которой можно вернуть всё как было
#   (вручную через `adb shell pm enable <пакет>` или через 99-restore.sh).
#
# Что сохраняем в backup/<timestamp>/:
#   - packages-system.txt    — список системных (встроенных) пакетов;
#   - packages-user.txt      — список пользовательских (установленных вами);
#   - packages-all.txt       — весь список для перекрёстной сверки;
#   - packages-disabled.txt  — список УЖЕ отключённых пакетов (на случай,
#                              если ТВ уже кто-то «ковырял»);
#   - home-activity.txt      — какой launcher выбран сейчас по умолчанию;
#   - build-info.txt         — модель, Android, fingerprint прошивки;
#   - manifest.txt           — краткий индекс содержимого бэкапа.
# =============================================================================

source "$(dirname "$0")/lib/common.sh"

log "=== Этап 1: бэкап текущего состояния ==="

# Если IP не задан явно, подхватим из .tv-host, который записал 00-prepare.sh.
if [[ -z "$TV_HOST" && -f "$ROOT_DIR/.tv-host" ]]; then
    TV_HOST="$(cat "$ROOT_DIR/.tv-host")"
fi

ensure_adb_connected

# Создаём свой каталог для этого бэкапа. Папка названа по времени,
# чтобы можно было сделать несколько бэкапов и не затирать друг друга.
BK="$BACKUP_DIR/$TS"
mkdir -p "$BK"
log "Складываю бэкап в: $BK"

# --- Прошивка и устройство --------------------------------------------------
# Собираем «паспорт» ТВ. Если потом окажется, что бэкап снят с не того
# устройства — это сразу будет видно по модели и fingerprint.
{
    echo "model:       $(adb_shell getprop ro.product.model      | tr -d '\r')"
    echo "brand:       $(adb_shell getprop ro.product.brand      | tr -d '\r')"
    echo "device:      $(adb_shell getprop ro.product.device     | tr -d '\r')"
    echo "android:     $(adb_shell getprop ro.build.version.release | tr -d '\r')"
    echo "sdk:         $(adb_shell getprop ro.build.version.sdk  | tr -d '\r')"
    echo "fingerprint: $(adb_shell getprop ro.build.fingerprint  | tr -d '\r')"
    echo "backup_ts:   $TS"
    echo "host_ip:     $TV_HOST"
} > "$BK/build-info.txt"
log "build-info.txt сохранён"

# --- Списки пакетов ---------------------------------------------------------
# pm list packages — выдаёт по строке на пакет в формате: 'package:имя'.
# Флаги:
#   -s = только системные (вшитые в прошивку);
#   -3 = только сторонние (установленные пользователем, например через Google Play);
#   -d = только отключённые (кто-то или что-то уже вызвал pm disable-user);
#   без флагов = все пакеты.
# tr -d '\r' убирает CR-символы, которые adb на некоторых прошивках добавляет
# в конец строк из-за эмуляции DOS-переводов строк.
adb_shell pm list packages -s | tr -d '\r' | sort > "$BK/packages-system.txt"
adb_shell pm list packages -3 | tr -d '\r' | sort > "$BK/packages-user.txt"
adb_shell pm list packages    | tr -d '\r' | sort > "$BK/packages-all.txt"
adb_shell pm list packages -d | tr -d '\r' | sort > "$BK/packages-disabled.txt"

# Печатаем количество строк в каждом списке — удобно для беглой проверки.
log "Системных пакетов:        $(wc -l < "$BK/packages-system.txt")"
log "Пользовательских пакетов: $(wc -l < "$BK/packages-user.txt")"
log "Всего пакетов:            $(wc -l < "$BK/packages-all.txt")"
log "Уже отключено:            $(wc -l < "$BK/packages-disabled.txt")"

# --- Текущий домашний экран ------------------------------------------------
# cmd package resolve-activity … возвращает описание активности в формате,
# из которого видно и имя пакета, и имя класса активности. Полезно для
# точного знания, что было до нашего вмешательства.
adb_shell cmd package resolve-activity --brief \
    -c android.intent.category.HOME | tr -d '\r' > "$BK/home-activity.txt"
log "home-activity.txt:"
cat "$BK/home-activity.txt" | tee -a "$LOG_FILE"

# --- Короткий манифест бэкапа ----------------------------------------------
# Чтобы человек, открывший папку бэкапа через месяц, сразу понимал,
# что это за файлы.
cat > "$BK/manifest.txt" <<EOF
Содержимое бэкапа ($TS):
  build-info.txt        — модель и Android ТВ, с которого сделан бэкап.
  packages-system.txt   — все системные пакеты (встроены в прошивку).
  packages-user.txt     — все пользовательские пакеты (из Google Play / sideload).
  packages-all.txt      — объединённый список, для сверки.
  packages-disabled.txt — пакеты, уже отключённые ДО нашего вмешательства.
  home-activity.txt     — какой launcher был выбран как домашний экран.

Как использовать для ручного восстановления отдельного пакета:
  adb shell pm enable <имя пакета без 'package:'>

Как использовать для полного отката через проект:
  ./scripts/99-restore.sh
EOF

log "---"
log "Бэкап готов: $BK"
log "Можно переходить к 02-debloat.sh"
