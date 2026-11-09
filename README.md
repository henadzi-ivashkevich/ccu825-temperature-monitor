# CCU825 Temperature Monitor

**Repository name:** `ccu825-temperature-monitor`

**Description:** Android app and Google Apps Script integration for viewing CCU825 sensor history from Google Sheets, with configurable 16 inputs and touch-based chart inspection.

Android-приложение и интеграция Google Apps Script для просмотра истории CCU825 из Google Sheets. В приложении можно выбрать любые из 16 входов, переименовать их, строить график за произвольный период или последние 15, 30 и 60 дней, а также просматривать срез показаний касанием графика.

## Структура

```text
ccu825-temperature-monitor/
├── android/                  # Android Studio / Kotlin / Jetpack Compose project
│   ├── app/
│   ├── gradle/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── apps-script/
    ├── README.md
    ├── ccu-extra-columns.gs   # Дополнительные параметры из ответа CCU
    └── temperature-history.gs # GET endpoint для приложения
```

## Быстрый старт

1. В Google Apps Script добавьте функции из [`apps-script/`](apps-script/README.md) в проект, который уже записывает CCU825 в таблицу. Существующие `doPost(e)` и запрос CCU сохраняются.
2. Разверните новую версию веб-приложения Apps Script и скопируйте URL `/exec`.
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
