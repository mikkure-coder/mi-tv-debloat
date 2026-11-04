package org.mitvdebloat.vitrina;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Служба для кнопки «Питание» в ярлыках. Обычному приложению система не даёт
 * ни выключить устройство, ни открыть диалог питания: права REBOOT и
 * DEVICE_POWER есть только у системных приложений. Служба специальных
 * возможностей может выполнять «глобальные действия» — мы используем ровно
 * одно: открыть системный диалог питания (как по долгому нажатию кнопки
 * пульта), а «Выключить» или «Перезагрузить» уже выбирает человек.
 * События экрана служба не получает и содержимое окон не читает.
 * Включает её скрипт 08 через ADB, снимает 99 (решение владельца 2026-11-04).
 */
public class PowerService extends AccessibilityService {

    private static PowerService self;

    @Override public void onServiceConnected() { self = this; }

    @Override public boolean onUnbind(Intent intent) { self = null; return false; }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    /** Открыть системный диалог питания. false — служба не включена. */
    static boolean powerDialog() {
        return self != null && self.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }
}
