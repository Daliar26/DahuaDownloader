# Dahua Downloader

Android-проект для разработки менеджера архива Dahua.

Устройство:
DHI-NVR2104-P-4KS2

## Текущий статус

Прототип интерфейса и очереди заданий.

Реальная загрузка видео пока не подключена.

Для неё потребуется официальный совместимый Dahua Android P2P/NetSDK,
проверка поддержки модели и прошивки, а также реализация механизма
поиска архива и скачивания.

DMSS P2P не предоставляет автоматически стороннему приложению
готовую авторизованную сессию.

Не публикуйте пароли и закрытые SDK в GitHub.

## Сборка

Откройте папку проекта в Android Studio.
Нужны JDK 17 и Android SDK Platform 35.

## GitHub

```bash
git init
git add .
git commit -m "Initial Dahua Downloader"
git branch -M main
git remote add origin https://github.com/USERNAME/REPOSITORY.git
git push -u origin main
```
