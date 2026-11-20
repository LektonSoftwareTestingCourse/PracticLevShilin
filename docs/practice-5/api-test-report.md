# API-тесты СМП — практика 5

Проверки используют Java 21, Maven, JUnit 5, REST Assured, Hamcrest, Awaitility и JSON Schema. Данные создаются HTTP-запросами. Доступ к PostgreSQL и RabbitMQ из тестов отсутствует. Источники ожиданий — [API-контракт](../api-spec.md), [OpenAPI](../api/openapi.yaml), [маршруты Gateway](../../services/gateway/src/main/resources/application.yml), контроллеры и DTO. При расхождении отдельно указано, какое поведение закрепляет основной набор и какой контракт должен получить клиент.

## 1. Покрытие

| Сервис и маршрут | Проверяемые HTTP-статусы | Где смотреть |
|---|---|---|
| Gateway `GET /health` и прямые health сервисов | 200, 503 для дополнительного Gateway с недоступным Switch | `InfrastructureTest`, `AdditionalContractsTest` |
| Gateway `POST /api/transactions` | 200, 400, 429, 500 при недоступном Switch. Контракт ожидает 503 в последнем случае | `AuthorizationTest`, `GatewayValidationTest`, `FaultTest` |
| Switch `POST /api/internal/route` | 200 | `AuthorizationTest` |
| Authorization `POST /api/internal/authorize` | 200, 400, 403, 404, 422, 503 при ошибках CMS, 500 при отказе записи лимитов (D13) | `AuthorizationTest`, `InternalValidationTest`, `DependencyFaultTest` |
| Authorization `POST /api/internal/rollback` | 200, 400, 404, 409, 503 | `AuthorizationTest`, `InternalValidationTest`, `DependencyFaultTest` |
| CMS `POST /api/cards`, `POST /api/cards/generate` | 201, 400, 404 для неизвестного BIN | `CardsTest`, `CardBoundaryTest` |
| CMS `GET /api/cards`, `GET/PATCH/DELETE /api/cards/{pan}` | 200, 204, 400, 404, 413 в зависимости от метода. 27 попарных комбинаций списка карт | `CardsTest`, `AdditionalContractsTest`, `CardBoundaryTest` |
| CMS `POST /api/cards/{pan}/reserve`, `POST /api/cards/{pan}/rollback` | 200, 400, 402 для reserve, 404, 409 | `CardsTest`, `CardBoundaryTest` |
| CMS `POST /api/cards/bulk-update` | 200, 400 | `CardsTest`, `AdditionalContractsTest` |
| CMS прямой `GET/POST /api/bins`, `GET/DELETE /api/bins/{bin}` | 200, 201, 204, 404, 409 в зависимости от метода | `AdditionalContractsTest` |
| Logger `POST /api/internal/log` | 200, 201, 400, 409, 500, 503. Для GET на этом пути 405, для отсутствующего пути 404 | `LoggerTest`, `DependencyFaultTest` |
| Logger `GET /api/transactions/search`, `GET /api/transactions/export` | 200, 400 | `LoggerTest`, `SearchPairwiseTest`, `KnownDefectsTest` |
| Logger `GET /api/dashboard/stats`, `/recent`, `/charts` | 200, 400 для recent и charts | `DashboardTest` |
| Terminal `POST /api/simulator/terminal/run`, `/start-continuous`, `/stop` | 200, 400 для run, 422 при повторном start, 500 для устаревших путей | `SimulatorTest`, `AdditionalContractsTest` |
| Merchant `POST /api/simulator/merchant/run`, `/fee`, `GET /api/simulator/merchants` | 200, 400, 404 для run и fee, 200 напрямую и 404 через Gateway для списка | `SimulatorTest` |
| BIN Lookup `GET /api/bin/{bin}` | 200, 404, 500 с `fail=true` | `InfrastructureTest` |
| Notification `GET /api/notifications` | 200 | `InfrastructureTest` |
| Gateway `GET /{switch,logger,terminal,merchant,cards}-docs` | 200 | `InfrastructureTest` |

Сервисы PostgreSQL и RabbitMQ обеспечивают работу стенда. Web Dashboard раздаёт UI и проксирует WebSocket через nginx. WebSocket и браузерный UI лежат вне синхронного REST-набора. Актуальные пути терминала — `start-continuous` и `stop`. Пути `start` и `status` присутствуют в OpenAPI, но не имеют контроллеров.

## 2. Бизнес-отказы

| Код | Причина | Подготовка и путь | Ожидание |
|---|---|---|---|
| `14` | `CARD_NOT_FOUND` | Известный BIN и отсутствующий PAN. Отдельно неизвестный BIN, который отклоняет Switch | Gateway 200, прямой Authorization 404 |
| `05` | `CARD_BLOCKED` | `PATCH` своей карты в `BLOCKED` | Authorization 403, Gateway 200 |
| `05` | `CARD_INACTIVE` | `PATCH` своей карты в `INACTIVE` | Authorization 403, Gateway 200 |
| `54` | `CARD_EXPIRED` | `PATCH` в `EXPIRED` или дата запроса после последнего дня действия `ACTIVE` карты | Authorization 403 |
| `51` | `INSUFFICIENT_FUNDS` | Баланс на единицу меньше суммы | Authorization 422, Gateway 200 |
| `61` | `EXCEEDS_AMOUNT_LIMIT` | Дневной лимит ниже суммы. Отдельно дневной лимит 100, месячный 199, две операции по 100 на разных датах одного месяца. После отказа операция на 99 проходит | Authorization 400 для отказа, 200 на границе. Gateway 200 с доменным отказом |
| `96` | `System error` | Дополнительный Switch не может отправить сообщение в брокер после успешного резерва | Gateway 200, баланс восстановлен, запись не появляется |
| `14` | `TRANSACTION_NOT_FOUND` | Неизвестный RRN при откате | Authorization 404 |
| `05` | `ALREADY_ROLLED_BACK` | Успех первого отката и повтор того же запроса | Authorization 409 |
| `96` | `SERVICE_UNAVAILABLE` | CMS возвращает 503 при чтении карты, резерве или откате | Authorization 503 во всех трёх случаях |
| `05` | `UNKNOWN_REASON` | CMS отдаёт карту с некорректным статусом в JSON | Authorization 400 |
| `96` | `RESERVATION_FAILED` | Чтение карты прошло, резерв получает 409 от CMS | Authorization 503 |
| `96` | `ROLLBACK_FAILED` | CMS отклоняет откат с 422 | Authorization 503 |
| `05`, `51`, `14` | `CARD_BLOCKED`, `INSUFFICIENT_FUNDS`, `CARD_NOT_FOUND` | Дополнительные пути. Чтение с 402, резерв с 402, резерв с 404 или 400 после успешного чтения | Authorization 403, 422, 404 соответственно |
| `96` в коде сервиса | `DB_UNAVAILABLE` | Изолированный Authorization подключается к БД с запретом записи. INSERT лимита вызывает SQLSTATE 25006 | Фактически клиент получает HTTP 500 без доменного ответа (D13) |

В технических сценариях `DependencyFaultTest` использует отдельный Authorization с управляемой CMS. Правило ответа CMS выбирается по суффиксу синтетического PAN. Отказ записи Logger воспроизводится через отдельный экземпляр с JDBC-сессией только для чтения. Тест проверяет 503, причину, `retryAfterMs` и отсутствие записи в основном Logger.

## 3. Валидация

| Вход | Правило и классы | Проверки |
|---|---|---|
| Gateway, обязательные поля | `null`, пустая строка, пробел; `mti=0100`, PAN 16 цифр, MCC 4 цифры, валюта 3 символа | `GatewayValidationTest`, 48 запусков включая неверный JSON и типы |
| Gateway, `amount` | отсутствует, −1, 0, допустимая положительная сумма | `GatewayValidationTest`, `AuthorizationTest` |
| Authorization, строковые поля | обязательность, точные длины и соседи `N−1`/`N+1`; у `terminalType` обязательности нет | `InternalValidationTest` |
| Rollback, PAN/RRN/amount | точная длина, отсутствие, буквы, отрицательные значения и ноль | `InternalValidationTest` |
| Logger, обязательность, форматы и длины | `null`/пусто/пробел, точная длина или превышение максимума, буквы в цифровых полях; ноль и отрицательные числа | `LoggerTest.invalidTransaction` |
| CMS, создание, PATCH, генерация, резерв, откат | BIN 6 цифр, PAN 16, RRN 12, обязательные поля, валюта 3 цифры, суммы −1/0/1, лимиты −1/0 и порядок дневного/месячного, генерация 0/1/10001 | `CardsTest`, `CardBoundaryTest`, `AdditionalContractsTest` |
| Поиск и экспорт | `limit` 1–500, `offset` от 0, статус, даты и обратный диапазон. Пары классов значений проверены для всех десяти параметров поиска | `LoggerTest`, `SearchPairwiseTest`, `KnownDefectsTest` |
| Симуляторы и dashboard | минимальные `count` и `tps`, `recent.limit` 1–500, `hour`/`day` и неизвестная гранулярность | `SimulatorTest`, `DashboardTest` |

## 4. Расхождения

| Код | Ожидание / источник | Наблюдение / реализация | Классификация |
|---|---|---|---|
| D01 | MTI ответа `0110`, API-контракт | В успешном ответе `0100`, `AuthorizationResponse.approved` копирует MTI запроса | Дефект ответа |
| D02 | `issuerId` опционален в публичном запросе, API-контракт | Switch добавляет его, прямой Authorization проверяет `@NotBlank` | Различие публичного и внутреннего API |
| D03 | По ответу можно определить поле и правило, критерии ТЗ | CMS возвращает текст первого нарушения без имени поля, `GlobalExceptionHandler` | Дефект диагностики |
| D04 | `/api/simulator/merchants` доступен через Gateway, OpenAPI | Прямой сервис отвечает 200, Gateway 404. Префикс маршрута — `/api/simulator/merchant/**` | Дефект маршрута |
| D05 | После DELETE карточка не читается из старого кэша | Gateway отвечает 200 из кэша. Фильтр инвалидирует кэш только на 200/201 | Дефект кэша |
| D06 | Дневной лимит не выше месячного, инвариант `Card.withData` | Создание с нарушением инварианта отвечает 201, проверка срабатывает при PATCH | Дефект валидации |
| D07 | Откат ограничен суммой резерва, обратный диапазон объяснён в ошибке | В коде суммы отката не сравниваются. Logger собирает только ошибки поля, отбрасывая ошибку всего объекта | Проверяются строгими сценариями `KnownDefectsTest` |
| D08 | Ответы валидации Authorization показывают нарушение | Стандартный Spring `400` с `path`, `status`, `timestamp` и `error` | Дефект диагностики внутреннего API |
| D09 | Недоступный Switch → HTTP 503, `docs/api-spec.md` | Реальный запрос при отказе подключения получил Spring HTTP 500 | Дефект обработки зависимости |
| D10 | `POST /terminal/start` и `GET /terminal/status` перечислены в OpenAPI | В `TerminalSimulatorController` есть `run`, `start-continuous`, `stop`. Для прежних путей общий обработчик возвращает HTTP 500. Серия таких ответов через Gateway может открыть автоматический выключатель и привести к 503 | Устаревшие пути и неверная классификация ошибки |
| D11 | Создание карты с уже существующим PAN → 409, API-контракт | PAN назначает CMS, клиент не может передать этот ключ. Коллизии повторяются внутренним генератором | Невоспроизводимый через вход публичного API контракт |
| D12 | `GET /api/cards/{pan}` документирует expiry в MMYY | DTO использует `YearMonth`. Тест даты поддерживает фактический формат | Расхождение сериализации |
| D13 | `AuthServiceImpl` формирует доменный отказ `96/DB_UNAVAILABLE`, контроллер мапит неизвестную причину на 400 | При read-only сессии INSERT помечает транзакцию на откат. `UnexpectedRollbackException` заменяет доменный ответ на HTTP 500 без кода и причины | Дефект обработки транзакции, подтверждён HTTP и журналом Authorization |
| D14 | Для неподдерживаемого типа тела ожидается 415 по HTTP | `application/xml` попадает в общий обработчик Logger. Возвращается 500 с `Unexpected transaction logger error` | Дефект классификации ошибки клиента |

## 5. Обоснование достаточности

Данные привязаны к собственным PAN и UUID. Сравнения тела отличают отказы с одинаковым доменным кодом. Положительное ожидание проверяет запись по PAN и STAN через Gateway, а отрицательное наблюдает отсутствие записи в течение всего окна. Повторная доставка проверяется для новой записи, точного повтора и изменённой нагрузки с прежним UUID.

`SearchPairwiseTest` отправляет 27 комбинаций для десяти параметров поиска. Для восьми фильтров классы — отсутствует, совпадает, исключает. Для `limit` используются отсутствие, 1 и 500, для `offset` — отсутствие, 0 и 1. Перед отправкой запросов тест проверяет, что для каждой из 45 пар параметров матрица содержит все девять пар уровней. Исход оценивается по UUID двух собственных записей, размеру страницы и HTTP 400 при обратном диапазоне. Границы вне допустимой области и отдельная пустая выборка находятся в `LoggerTest`. По тому же принципу проверены все семь параметров списка карт и три параметра графиков. Для карт дополнительно проверяются верхняя граница страницы 10 000 и пустая страница при большом смещении. Для графиков проверяются обе гранулярности, включение нижней границы, исключение верхней и отказ при обратном диапазоне.

Проверка комиссии запускает одну операцию симулятора, находит её по STAN, RRN и времени в Logger, извлекает ключ и отдельно рассчитывает ожидаемую сумму по тарифу мерчанта. Проверки CMS сравнивают фактический баланс и лимиты до и после отказов. В основном наборе не создаётся партия из 10 000 карт ради нагрузки. Проверяются минимальная успешная партия и отказ на первом значении сверх максимума.

Коллизия PAN при создании недоступна для задания через HTTP (D11). Специфический обработчик нарушения целостности Logger требует нарушения ограничения БД, отличного от совпадения ключа. Для валидных DTO схема и валидация не дают такого отдельного сценария без искусственного изменения схемы. Ручная остановка основной базы, прямые записи в БД, тестирование UI и нагрузка на весь стенд в набор не входят.

## 6. Доказательства

На локальном стенде Docker Compose 20.11.2026 команда `practice-5.ps1 -SkipStack -Faults -Runs 3` дала три последовательных `BUILD SUCCESS`. В каждом прогоне выполнена 341 проверка, 0 падений, 0 ошибок, 0 пропусков. Checkstyle не нашёл нарушений. Отдельные JUnit XML сохранены в `services/api-tests/target/evidence/06e13c038c5e4cd2a8c38c2940dde5b8/run-{1,2,3}`. Эти артефакты локальные. Ссылка на GitHub Actions появится после публикации ветки `practice-5-shilin` и выполнения workflow.

| Проверка | Как воспроизвести | Результат |
|---|---|---|
| `AuthorizationTest.approvalAndBalance` | В копии проверки заменить `00` на `99` | 3 из 3 упали на сравнении кода: фактически `00` |
| `GatewayValidationTest.rejectedField` | В копии проверки заменить `VALIDATION_ERROR` на `WRONG_ERROR` | 43 из 43 упали на сравнении ошибки: фактически `VALIDATION_ERROR` |
| `LoggerTest.duplicateDeliveryIsIdempotent` | В отдельной сборке Logger пропустить предварительную проверку существующей записи | 1 из 1 упал: повтор вернул `201` при ожидании `200` |

Все три мутации повторно воспроизведены на Compose 20.11.2026. Журналы повторной проверки находятся в `services/api-tests/target/mutations/eb906e55800b4d31857ec9db348dad1b`. Скрипт проверяет, что JUnit зарегистрировал падения проверок и ноль ошибок исполнения. Отдельный прогон `KnownDefectsTest` воспроизвёл семь расхождений из семи: MTI, имя поля в ошибке CMS, дневной и месячный лимиты при создании, кэш после удаления, маршрут списка мерчантов, текст ошибки диапазона и сумму отката. В основном прогоне эти строгие проверки исключены тегом `defect`.

## 7. Запуск

```powershell
# Три последовательных прогона со сценариями сбоев на поднятом Compose
.\scripts\practice-5.ps1 -SkipStack -Faults -Runs 3

# Запуск с подъёмом стенда
.\scripts\practice-5.ps1 -Faults -Runs 3

# Строгие проверки известных дефектов, ожидаются падения
.\scripts\practice-5.ps1 -SkipStack -Defects

# Три мутации на временных копиях тестов и Logger
.\scripts\practice-5-mutations.ps1
```

Если `mvn` отсутствует в PATH, укажите `-Maven` с путём до `mvn.cmd`. Для основного скрипта используется JDK 21 и Docker Compose. Прямой запуск Maven пишет отчёты в `services/api-tests/target/surefire-reports`. Скрипт сохраняет результаты в `services/api-tests/target/evidence`, журналы мутаций находятся в `services/api-tests/target/mutations`. GitHub Actions автоматически запускается в ветках `practice-5-*`, при изменениях в PR и вручную. Ссылку на реальный прогон и текст отчёта добавляют в Issue с меткой `practice-5` после проверки работы.
