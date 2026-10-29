# А4. Sequence — happy path, declined, reversal

**Чего не хватало:** declined и компенсации при сбое публикации, разграничения ответа клиенту и обработки очереди.
**Что добавлено:** три ветки фактического исполнения и явно отделённое требование MTI 0400 / confirm 2s.

```mermaid
sequenceDiagram
    autonumber
    actor C as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant CMS as Card-Management
    participant B as BIN Lookup
    participant DB as PostgreSQL
    participant M as Merchant Acquirer
    participant Q as RabbitMQ
    participant L as Logger
    participant O as CMS OutboxProcessor
    participant N as Notification
    C->>G: sync HTTP POST /api/transactions, mti=0100
    G->>G: TransactionRequestValidator
    G->>S: sync HTTP POST /api/internal/route
    S->>S: BIN → issuerId, normalize
    S->>A: sync HTTP POST /api/internal/authorize
    A->>CMS: sync HTTP GET /api/cards/{pan}
    CMS->>DB: sync JDBC SELECT card
    DB-->>CMS: Card
    CMS-->>A: CardModel
    A->>B: sync HTTP GET /api/bin/{bin}
    B-->>A: issuerId либо Optional.empty при ошибке
    Note over A,B: Результат BIN lookup только логируется, issuerId из Switch сохраняется
    alt declined: Card.status=BLOCKED
        A-->>S: HTTP 403, DECLINED / 05 / CARD_BLOCKED
        S->>M: sync HTTP запрос комиссии
        M-->>S: комиссия либо fallback
        S-->>Q: async AMQP smp.transactions / transaction.log: DECLINED
        Note over S,Q: log() возвращает boolean после convertAndSend, ожидания confirm нет
        par Ответ клиенту
            S-->>G: HTTP 200, DECLINED / 05
            G-->>C: HTTP 200, DECLINED / 05
        and Независимая доставка
            Q-->>L: async AMQP transaction-log
            L->>DB: sync JDBC сохранить DECLINED
        end
    else ACTIVE, срок и баланс допустимы
        A->>DB: sync JDBC upsertLimitUsage: daily и monthly
        DB-->>A: 1 строка обновлена
        A->>DB: sync JDBC блок rrn_seq, если локальный блок исчерпан
        A->>CMS: sync HTTP POST /api/cards/{pan}/reserve
        CMS->>DB: sync JDBC: lock card, reservation, баланс минус amount, outbox PENDING
        DB-->>CMS: commit
        CMS-->>A: HTTP 200, новый баланс
        A-->>S: HTTP 200, APPROVED / 00, rrn, authCode
        S->>M: sync HTTP запрос комиссии
        M-->>S: комиссия либо fallback
        S-->>Q: async AMQP smp.transactions / transaction.log: APPROVED
        alt happy path: convertAndSend не бросил AmqpException
            par Ответ клиенту
                S-->>G: HTTP 200, APPROVED / 00
                G-->>C: HTTP 200, APPROVED / 00
            and Независимая доставка в Logger
                Q-->>L: async AMQP transaction-log
                L->>DB: sync JDBC сохранить APPROVED
            end
        else reversal: convertAndSend бросил AmqpException
            Note over S,A: Реальный транспорт компенсации — отдельный rollback endpoint, без поля mti
            S->>A: sync HTTP POST /api/internal/rollback: rrn, pan, amount
            A->>CMS: sync HTTP POST /api/cards/{pan}/rollback
            CMS->>DB: sync JDBC: баланс плюс amount, reservation ROLLED_BACK, outbox
            CMS-->>A: HTTP 200 при успешной компенсации
            A-->>S: результат rollback
            Note over A,DB: limit_usage НЕ уменьшается в rollback()
            S-->>G: HTTP 200, DECLINED / 96
            G-->>C: HTTP 200, DECLINED / 96
            Note over S,L: Новый статус транзакции в Logger после rollback не публикуется
        end
    end
    opt Независимый фоновый процесс после событий CMS
        O->>DB: sync JDBC выбрать outbox PENDING
        O-->>Q: async AMQP smp.card-events / card.EventClass
        O->>DB: sync JDBC PROCESSED после convertAndSend без ошибки
        Q-->>N: async AMQP card-notifications
        N->>DB: sync JDBC сохранить card_notifications
    end
    Note over S,Q: Требование: ACK максимум 2s, NACK/timeout → reversal 0400 и DECLINED 96. Ожидание ACK в реализации отсутствует.
```

Легенда sequence: ->> — синхронный вызов; -->> с подписью HTTP/данные — возврат; -->> с подписью AMQP — асинхронное сообщение. Тип связи определяется подписью, поскольку пунктир используется также для возвратов. Ветки par показывают отсутствие гарантированного порядка: Logger может записать результат как до ответа клиенту, так и после него.

## Проверка сценариев и расхождений

| Участок | Код / вывод |
|---|---|
| Вход и маршрутизация | [TransactionRequestValidator.java](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [application.yml](../../../services/gateway/src/main/resources/application.yml), [RoutingService.java](../../../services/switch/src/main/java/com/processing/service/RoutingService.java), [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| Порядок CMS → BIN → статус → срок → баланс → лимиты → reserve | [AuthServiceImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java); SQL лимитов — [LimitUsageRepository.java](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java) |
| BLOCKED | [DeclineOutcome.java](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java): CARD_BLOCKED / 05; [AuthControllerImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java): HTTP 403; [AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) принимает тело non-2xx как бизнес-ответ |
| Внешний HTTP 200 | [RouteController](../../../services/switch/src/main/java/com/processing/controller/RouteController.java) возвращает ResponseEntity.ok даже для DECLINED |
| Комиссия после Authorization | [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [MerchantAcquirerClient.java](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java); выполняется и для нормального бизнес-отказа |
| Публикация и rollback | [LoggerClient.java](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java) ловит AmqpException; [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java) вызывает rollback только после APPROVED и log=false |
| Payload компенсации | [AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) создаёт [RollbackRequest.java](../../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java) с rrn/pan/amount; MTI в этом DTO отсутствует |
| Резерв/компенсация/outbox | [CardServiceImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [Reservation.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java), [CardEventNotifierImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java), [OutboxProcessor.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [OutboxEventProcessorImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java) |
| Сохранение потребителями | [TransactionLogListener.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [TransactionService.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java), [CardEventListener.java](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |

**Что означает «reversal (0400)» в задании:** [03-switch.md](../../../tz/03-switch.md) требует MTI 0400 и ожидание confirm 2s; [AuthorizationRequest.java](../../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java) содержит forReversal(), но фактический [AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) этот метод не вызывает. Внешний Gateway принимает только 0100 ([TransactionRequestValidator.java](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java)). Таким образом, на схеме 0400 обозначен как требуемая семантика, а действующий rollback endpoint показан буквально по коду. Нельзя рисовать уже реализованную передачу 0400.

**Ограничения:** correlated в конфигурации не означает ожидание ACK в log(). При сбое самой компенсации Switch всё равно возвращает 96: успешное восстановление баланса не гарантировано ([RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java)). Отсутствие confirm также не доказывает отсутствие принятого сообщения — запись APPROVED в Logger и отказ клиенту могут разойтись. Поведение под сетевыми отказами требует запуска тестов; схема основана на статическом чтении.
