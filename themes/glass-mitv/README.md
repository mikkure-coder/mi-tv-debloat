# glass-mitv — тема лаунчера CleanYourTV для нашего Mi TV 4S

Основа — тема `themes/glass` из https://github.com/Gathin23/cleanyourtv
(коммит `204143007f0b`, лицензия MIT, © 2026 Gathin): тёмно-синий фон,
«верхняя полка» с названием приложения, стеклянная панель — ближе всего к
Apple TV. `wallpaper.jpg` взят оттуда без изменений.

Изменено у нас (2026-10-25): `dock_apps` — приложения владельца по частоте
использования (VK Видео, Rutube, Кинопоиск, Okko, KION, YouTube) вместо
Netflix/Disney+/HBO.

Ставится: `./scripts/07-theme.sh` (по умолчанию эта тема).
Откат: `./scripts/07-theme.sh --reset` или `./scripts/99-restore.sh`.
