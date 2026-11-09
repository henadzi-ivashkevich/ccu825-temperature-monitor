# CCU825 Temperature Monitor

**Repository name:** `ccu825-temperature-monitor`

**Description:** Android app and Google Apps Script integration for viewing CCU825 sensor history from Google Sheets, with configurable 16 inputs and touch-based chart inspection.

Android-приложение и интеграция Google Apps Script для просмотра истории CCU825 из Google Sheets. В приложении можно выбрать любые из 16 входов, переименовать их, строить график за произвольный период или последние 15, 30 и 60 дней, а также просматривать срез показаний касанием графика.

## Структура

```text
ccu825-temperature-monitor/
├── android/                  # Android Studio / Kotlin / Jetpack Compose project
├── apps-script/
│   ├── README.md
│   └── Code.gs               # POST записи + GET истории
└── docs/
    └── automate-flow.md      # Схема блоков и передача данных/токена
```

## Быстрый старт

1. Настройте секрет `API_TOKEN` в Script Properties и в POST-поле потока Automate. См. [`apps-script/README.md`](apps-script/README.md) и [схему Automate](docs/automate-flow.md).
2. В Google Apps Script установите `Code.gs` и разверните новую версию веб-приложения.
3. Откройте папку `android/` в Android Studio, соберите и запустите приложение.
4. В настройках приложения укажите URL `/exec`, выберите входы и задайте им нужные названия.

## Сборка Android

Требуются Android Studio, JDK 17 и Android SDK Platform 37 / Build Tools 36.0.0.

```bash
cd android
./gradlew assembleDebug
```

Windows:

```bat
cd android
gradlew.bat assembleDebug
```

APK будет создан в `android/app/build/outputs/apk/debug/app-debug.apk`. Каталоги сборки и готовые APK исключены из Git.

## Данные таблицы

Первые 17 заголовков: `Время`, затем 16 входов в таком порядке:

1. `Объем`
2. `Дымовой 2эт`
3. `Люк УГБО`
4. `Улица °C`
5. `Протечка`
6. `2 этаж °C`
7. `Камин тепловой`
8. `Уровень УГБО`
9. `Гараж ворота`
10. `Гараж вибро`
11. `Гараж дым`
12. `1 этаж °C`
13–16. `Вход 13`–`Вход 16`

Интеграция также добавляет столбцы `Выход 1`–`Выход 7`, `Заряд батареи, %`, `Состояние батареи`, `Power` и `Температура платы, °C`. Секреты и URL развернутого Apps Script не храните в репозитории.

**Безопасность:** токен из текущего кода следует заменить. Новое значение не включено в этот репозиторий: оно настраивается отдельно в Script Properties и в Automate. Веб-метод истории GET в текущем `Code.gs` не проверяет токен; учитывайте это при настройке доступа.
