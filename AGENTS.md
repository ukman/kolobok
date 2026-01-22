# AGENTS.md

Этот файл — краткий конспект состояния проекта, решений и договоренностей. Нужен, чтобы быстро продолжать работу в будущих сессиях.

## Контекст проекта
- Проект kolobok: инструмент для Spring Data репозиториев.
- Основная аннотация: `@FindWithOptionalParams` (генерация default-методов и абстрактных комбинаций).
- Цели: поддержка Java 21, Spring Boot 2.x (с прицелом на Spring 3 в будущем), сохранение привычного API.
- Переезд на Java 11+ проверен (11/17/21/25).

## Текущая архитектура
- Модули: `kolobok-annotations`, `kolobok-transformer`, `kolobok-maven-plugin`, `kolobok-gradle-plugin`.
- Трансформер работает на байткоде (ASM), без javac internals.
- Версия ASM обновлена до 9.8 (нужно для Java 25).
- Релизная версия сейчас 0.2.4.

## Новая аннотация @DebugLog
Реализована через байткод-трансформацию (не Spring AOP).

Функции:
- лог входа/выхода/исключений и времени выполнения;
- использует существующий SLF4J логгер в классе (static field `log`, `logger`, `LOG`, `LOGGER`);
- при отсутствии логгера сборка должна падать;
- добавлены параметры:
  - `lineHeatMap`
  - `lineHeatMapOnException`
  - `subHeatMap`
  - `logDuration`
  - `aggregateChildren`
  - `logArgs`
  - `mask`
  - `maxArgLength`
  - `logLevel`
  - `logFormat`
  - `logThreadId`
  - `logThreadName`

Внимание:
- если в классе нет SLF4J логгера — сборка падает.
- для heat map логов **нужен runtime** доступ к `kolobok` jar.

### Параметры аргументов
- `logArgs` управляет выводом аргументов в entry-логах и JSON.
- `mask` маскирует аргументы по индексам (`"0,2-3"` или `"*"`).
- `maxArgLength` ограничивает длину строковых аргументов.
- `logLevel` задает уровень логов для entry/exit/heat map (по умолчанию `DEBUG`).
- `logFormat` переключает формат логов (`HUMAN` или `JSON`, по умолчанию `HUMAN`).
- `logThreadId`/`logThreadName` добавляют идентификатор/имя потока в логи.
- Аннотации `@DebugLogIgnore` и `@DebugLogMask` управляют скрытием/маскированием параметров и локальных переменных (локальные требуют debug symbols; сейчас захватываются только `int` и reference типы).
- Параметры `logLocals` и `logLocalsOnException` включают логирование всех локальных переменных (best-effort), кроме явно скрытых/маскированных.
- Глобальные дефолты DebugLog настраиваются без изменений в коде: приоритет аннотация > конфиг плагина > ENV/JVM > встроенные значения; ключи `kolobok.debuglog.*` и `KLB_DEBUGLOG_*`.

## Heat map (lineHeatMap)
Логируется JSON-объект с тепловой картой строк.
Формат (единый, дерево):
```json
{
  "traceId": "...",
  "method": "com.example.Foo#bar(Ljava/lang/String;I)Ljava/lang/String;",
  "lineHeatMap": { "100-103": 1, "104": 10 },
  "durationNs": 123,
  "children": []
}
```
Поддерживается вложенность: дочерние методы попадают в `children`.
traceId берется из MDC `traceId` (если есть), иначе UUID.
HUMAN-лог heatmap выводится многострочно с отступами и включает дочерние узлы.

## Важные детали
- `@DebugLog` требует, чтобы `kolobok` был в runtime classpath. В samples убран `provided`/`compileOnly`.
- `PersonRepository` в samples теперь наследуется от `JpaRepository` (а не `CrudRepository`), т.к. default-метод вызывает `findAll()` с `List` типом.
- Для Maven sample включен DEBUG только для `com.example.kolobok`.
- Трансформер можно отключить без изменения кода: Maven `-Dkolobok.skip=true`, Gradle `-Pkolobok.skip=true`.

## Скрипты запуска
- `run-java11.sh`, `run-java21.sh` в samples — запускают Maven/Gradle в offline режиме (`mvn -o`).
- Эти файлы исключены из git через `.gitignore`.

## Известные предупреждения
- `~/.m2/settings.xml` содержит `<server>` без корневого `<settings>` → warning.
- SLF4J warning про NOP provider в тестах (нормально для unit-тестов).

## Что уже сделано
- Добавлен `@DebugLog`, тепловая карта, nested JSON, traceId.
- Добавлены тесты для DebugLog и line-number проверки.
- Обновлены README, CHANGELOG, RELEASE_NOTES_0.2.4.
- Проверка на Java 11/17/21/25.

## Идеи/направления
- Уточнить/улучшить обработку `Iterable` vs `List` в `@FindWithOptionalParams`.
- Возможная опция: heat map в файл или JSON-отчет (сейчас — только лог).
