# Test-design ядра СМП: Authorization и Card-Management

## Исходные данные

**Дата прогона:** сентябрь 2026 г. Текущий месяц в формате MMYY — `0926`; `expiryDate` новой карты (текущая дата + 3 года) — `0929`.

**Денежные величины** указаны в копейках, как в API. Шаг граничных значений — 1 копейка.

### Требования

| ID | Сервис | Требование |
|---|---|---|
| AUTH-01 | Authorization | Карта не найдена в CMS (в том числе удалённая, DELETED) → DECLINED, `14` |
| AUTH-02 | Authorization | CMS недоступен → DECLINED, `05`, ISSUER_TIMEOUT |
| AUTH-03 | Authorization | Статус не ACTIVE → DECLINED: INACTIVE → CARD_INACTIVE, BLOCKED → CARD_BLOCKED, EXPIRED → `54` |
| AUTH-04 | Authorization | `expiryDate` (MMYY) раньше текущей даты → DECLINED, `54` |
| AUTH-05 | Authorization | `daily_amount` + amount > `dailyLimit` → DECLINED, `61` |
| AUTH-06 | Authorization | `monthly_amount` (сумма `daily_amount` за месяц) + amount > `monthlyLimit` → DECLINED, `61` |
| AUTH-07 | Authorization | amount > `availableBalance` → DECLINED, `51` |
| AUTH-08 | Authorization | Все проверки пройдены → APPROVED, `00`; `POST /api/cards/{pan}/reserve`; `limit_usage` увеличен на amount; RRN — 12 цифр; authCode — 6 символов `[A-Z0-9]` |
| AUTH-09 | Authorization | RRN уникален: при совпадении секунды инкрементируется `seq` |
| AUTH-10 | Authorization | Bin Lookup недоступен → используется `issuerId` из таблицы BIN Switch, авторизация продолжается |
| CMS-01 | Card-Management | `POST /api/cards`: PAN = `bin` + 9 случайных цифр + контрольная цифра Луна (16 цифр) |
| CMS-02 | Card-Management | `expiryDate` = текущая дата + 3 года, формат MMYY |
| CMS-03 | Card-Management | Новая карта: статус ACTIVE; поля сохранены; `availableBalance` = `initialBalance` |
| CMS-04 | Card-Management | `GET /api/cards/{pan}`: карта или `404`; карта DELETED → `404` |
| CMS-05 | Card-Management | `GET /api/cards`: `limit` (50), `offset` (0), фильтры `status`, `bin`; поле `total`; DELETED не возвращаются |
| CMS-06 | Card-Management | `PATCH /api/cards/{pan}`: меняются только переданные поля (статус, лимиты, баланс) |
| CMS-07 | Card-Management | `DELETE /api/cards/{pan}`: мягкое удаление, статус DELETED |
| CMS-08 | Card-Management | `POST /api/cards/generate`: `count` карт равномерно по `bins`; баланс 1 000 000–50 000 000; дневной лимит 5 000 000–30 000 000; месячный = дневной × 30; статусы только ACTIVE, INACTIVE, BLOCKED |
| CMS-09 | Card-Management | `POST /api/cards/{pan}/reserve`: `availableBalance` −= amount, `200` |

### Порядок проверок Authorization

Проверки выполняются строго по порядку, первая неуспешная определяет ответ: карта найдена → статус → срок действия → дневной лимит → месячный лимит → баланс → APPROVED. Поэтому в каждом негативном тест-кейсе все проверки до целевой должны проходить. При любом отказе баланс карты и `limit_usage` не меняются.

### Подготовка данных

| Что | Как |
|---|---|
| Карта | `POST /api/cards` с `bin` `400000`, `IVAN IVANOV`, `643` и значениями по умолчанию ниже |
| Статус, лимиты, баланс | `PATCH /api/cards/{pan}` |
| `expiryDate` | через `PATCH` не меняется; устанавливается в БД: `UPDATE cards SET expiry_date = '…' WHERE pan = '…'` |
| Использованные лимиты | одобренные авторизации за сегодня или записи в `limit_usage` за прошлые дни текущего месяца |
| Недоступность CMS / Bin Lookup | `docker compose stop card-management` / `docker compose stop bin-lookup` |

Значения по умолчанию: `dailyLimit` 15 000 000, `monthlyLimit` 300 000 000, `availableBalance` 100 000 000, amount 100 000, использовано за день и месяц — 0.

### Открытые вопросы к ТЗ

Для невалидных входных данных CMS (длина `bin`, пустое имя, отрицательные лимиты и баланс, `monthlyLimit` < `dailyLimit`, резервирование сверх баланса) реакция в ТЗ не описана. В тест-кейсах, помеченных ⚠, ожидается `400` и отсутствие изменений; ожидание нужно согласовать с командой CMS.

---

## Классы эквивалентности

Один представитель на класс. Негативный класс проверяется отдельным тест-кейсом, остальные параметры берутся из валидных классов.

### Authorization

| Поле | Класс | Вид | Представитель | Ожидаемый результат | ТК |
|---|---|---|---|---|---|
| Наличие карты | Карта существует | позитивный | карта ACTIVE по умолчанию | проверка продолжается | TC-AUTH-EC-01 |
| Наличие карты | Карта не существует | негативный | PAN с корректной суммой Луна, не созданный в CMS | DECLINED, `14` | TC-AUTH-EC-02 |
| Наличие карты | Карта удалена (DELETED) | негативный | карта после `DELETE` | DECLINED, `14` | TC-AUTH-EC-03 |
| Доступность CMS | Доступен | позитивный | CMS запущен | проверка продолжается | TC-AUTH-EC-01 |
| Доступность CMS | Недоступен | негативный | CMS остановлен | DECLINED, `05`, ISSUER_TIMEOUT | TC-AUTH-EC-04 |
| Доступность Bin Lookup | Доступен | позитивный | Bin Lookup запущен | APPROVED | TC-AUTH-EC-01 |
| Доступность Bin Lookup | Недоступен | позитивный | Bin Lookup остановлен | APPROVED (fallback `issuerId`) | TC-AUTH-EC-05 |
| Статус карты | ACTIVE | позитивный | ACTIVE | APPROVED, `00` | TC-AUTH-EC-01 |
| Статус карты | INACTIVE | негативный | INACTIVE | DECLINED, CARD_INACTIVE | TC-AUTH-EC-06 |
| Статус карты | BLOCKED | негативный | BLOCKED | DECLINED, CARD_BLOCKED | TC-AUTH-EC-07 |
| Статус карты | EXPIRED | негативный | EXPIRED | DECLINED, `54` | TC-AUTH-EC-08 |
| Срок действия | Текущий месяц или позже | позитивный | `0929` | APPROVED, `00` | TC-AUTH-EC-01 |
| Срок действия | Раньше текущего месяца | негативный | `0126` | DECLINED, `54` | TC-AUTH-EC-09 |
| Дневной лимит | использовано + amount ≤ `dailyLimit` | позитивный | 0 + 100 000 ≤ 15 000 000 | APPROVED, `00` | TC-AUTH-EC-01 |
| Дневной лимит | использовано + amount > `dailyLimit` | негативный | 14 950 000 + 100 000 | DECLINED, `61` | TC-AUTH-EC-10 |
| Месячный лимит | использовано + amount ≤ `monthlyLimit` | позитивный | 0 + 100 000 ≤ 300 000 000 | APPROVED, `00` | TC-AUTH-EC-01 |
| Месячный лимит | использовано + amount > `monthlyLimit` | негативный | 299 950 000 за прошлые дни, 0 за сегодня | DECLINED, `61` | TC-AUTH-EC-11 |
| Баланс | amount ≤ `availableBalance` | позитивный | 100 000 ≤ 100 000 000 | APPROVED, `00` | TC-AUTH-EC-01 |
| Баланс | amount > `availableBalance` | негативный | баланс 50 000 | DECLINED, `51` | TC-AUTH-EC-12 |
| RRN | Две авторизации в одну секунду | позитивный | два параллельных запроса по двум картам | оба APPROVED, RRN различаются | TC-AUTH-EC-13 |

### Card-Management

| Поле / операция | Класс | Вид | Представитель | Ожидаемый результат | ТК |
|---|---|---|---|---|---|
| Создание: `bin` | 6 цифр | позитивный | `400000` | `201`; PAN — 16 цифр, начинается с `400000`, проходит Луна | TC-CMS-EC-01 |
| Создание: `bin` | Не 6 цифр | негативный | `40000A` | ⚠ `400`, карта не создана | TC-CMS-EC-02 |
| Создание: `cardholderName` | Непустое, ≤ 255 символов | позитивный | `IVAN IVANOV` | `201`, сохранено | TC-CMS-EC-01 |
| Создание: `cardholderName` | Пустое | негативный | `""` | ⚠ `400`, карта не создана | TC-CMS-EC-03 |
| Создание: лимиты | `dailyLimit` > 0, `monthlyLimit` ≥ `dailyLimit` | позитивный | 15 000 000 / 300 000 000 | `201`, сохранены | TC-CMS-EC-01 |
| Создание: `dailyLimit` | ≤ 0 | негативный | −100 | ⚠ `400`, карта не создана | TC-CMS-EC-04 |
| Создание: `monthlyLimit` | < `dailyLimit` | негативный | 10 000 000 при daily 15 000 000 | ⚠ `400`, карта не создана | TC-CMS-EC-05 |
| Создание: `initialBalance` | ≥ 0 | позитивный | 100 000 000 | `201`, `availableBalance` = 100 000 000 | TC-CMS-EC-01 |
| Создание: `initialBalance` | < 0 | негативный | −100 | ⚠ `400`, карта не создана | TC-CMS-EC-06 |
| Создание: результат | `expiryDate`, статус | позитивный | любая новая карта | `expiryDate` = `0929`; статус ACTIVE | TC-CMS-EC-01 |
| Получение по PAN | Карта существует | позитивный | PAN новой карты | `200`, данные карты | TC-CMS-EC-07 |
| Получение по PAN | Корректный по Луну, не существует | негативный | PAN с верной суммой, не созданный | `404` | TC-CMS-EC-08 |
| Получение по PAN | Некорректный по Луну | негативный | PAN карты с изменённой контрольной цифрой | `404` | TC-CMS-EC-09 |
| Получение по PAN | Карта DELETED | негативный | PAN после `DELETE` | `404` | TC-CMS-EC-10 |
| Список | Без параметров | позитивный | `GET /api/cards` | `200`; не более 50 карт; `total` = число неудалённых | TC-CMS-EC-11 |
| Список | Фильтр `status` | позитивный | `?status=BLOCKED` | только BLOCKED | TC-CMS-EC-12 |
| Список | Фильтр `bin` | позитивный | `?bin=400002` | только PAN с `400002` | TC-CMS-EC-13 |
| Список | `offset` ≥ `total` | позитивный | `?offset={total}` | `cards` пуст, `total` не изменён | TC-CMS-EC-14 |
| Обновление | Одно поле | позитивный | `PATCH {"status":"BLOCKED"}` | `200`; изменён только статус | TC-CMS-EC-15 |
| Обновление | Карта не существует | негативный | `PATCH` по несозданному PAN | `404` | TC-CMS-EC-16 |
| Удаление | Карта существует | позитивный | `DELETE` | статус DELETED; нет в `GET /{pan}` и `GET /api/cards` | TC-CMS-EC-17 |
| Генерация | Валидный запрос | позитивный | `count` 100, 5 BIN | 100 карт, по 20 на BIN; диапазоны и статусы по CMS-08 | TC-CMS-EC-18 |
| Резервирование | amount ≤ `availableBalance` | позитивный | 150 000 при 100 000 000 | `200`; баланс 99 850 000 | TC-CMS-EC-19 |
| Резервирование | amount > `availableBalance` | негативный | 150 000 при 100 000 | ⚠ `400`, баланс не изменён | TC-CMS-EC-20 |

---

## Граничные значения

Для каждой границы берутся три точки: ниже границы, на границе (ON) и выше (OFF). Для лимитов и баланса граница включительна: сумма, равная лимиту или балансу, одобряется.

### Authorization

| Граница | Условия | Ниже | ON | Выше (OFF) | ТК |
|---|---|---|---|---|---|
| `dailyLimit` 15 000 000 | использовано за день 14 900 000 | amount 99 999 → APPROVED | 100 000 → APPROVED | 100 001 → DECLINED `61` | TC-AUTH-BV-01…03 |
| `monthlyLimit` 300 000 000 | использовано за прошлые дни месяца 299 900 000, за сегодня 0 | amount 99 999 → APPROVED | 100 000 → APPROVED | 100 001 → DECLINED `61` | TC-AUTH-BV-04…06 |
| `availableBalance` 100 000 | — | amount 99 999 → APPROVED | 100 000 → APPROVED | 100 001 → DECLINED `51` | TC-AUTH-BV-07…09 |
| `expiryDate` = текущий месяц `0926` | карта ACTIVE | `0826` → DECLINED `54` | `0926` → APPROVED | `1026` → APPROVED | TC-AUTH-BV-10…12 |

### Card-Management

| Граница | Ниже | ON | Выше (OFF) | ТК |
|---|---|---|---|---|
| Длина PAN = 16 (`GET /api/cards/{pan}`) | первые 15 цифр PAN → `404` | PAN → `200` | PAN + `0` (17) → `404` | TC-CMS-BV-01…03 |
| Длина `bin` = 6 (`POST /api/cards`) | `40000` → ⚠ `400` | `400000` → `201`, PAN 16 цифр | `4000000` → ⚠ `400` | TC-CMS-BV-04…06 |
| Длина `cardholderName` ≤ 255 | 254 → `201` | 255 → `201` | 256 → ⚠ `400` | TC-CMS-BV-07…09 |
| Формат `expiryDate`: текущая дата + 3 года | `0829` / `0928` — дефект | `0929` | `1029` / `0930` — дефект | TC-CMS-BV-10 |
| Резервирование: `availableBalance` 100 000 | amount 99 999 → баланс 1 | 100 000 → баланс 0 | 100 001 → ⚠ `400`, баланс не изменён | TC-CMS-BV-11…13 |

`expiryDate` генерируется сервисом и не передаётся на вход, поэтому граница проверяется как точное ожидаемое значение: сдвиг на месяц или год — ошибка расчёта. Корректность PAN по Луну проверяется в каждом тест-кейсе создания.

---

## Попарное тестирование

Модели и наборы лежат в `docs/practice-2/pict/`. Генерация:

```text
pict model.txt > cases.txt
pict model-card-management.txt > cases-card-management.txt
```

### Authorization

Модель: `pict/model.txt`, 7 параметров. Полный перебор: 4 × 3 × 3 × 3 × 3 × 3 × 2 = 1 944 комбинации, попарный набор — **20 строк** (`pict/cases.txt`).

| Параметр | Значения | Конкретные данные (amount = 100 000) |
|---|---|---|
| `card_status` | ACTIVE, INACTIVE, BLOCKED, EXPIRED | `PATCH` статуса |
| `expiry` | valid, current_month, expired | `0929`, `0926`, `0826` |
| `amount_vs_daily` | below, equal, above | использовано за день: 0 / 14 900 000 / 14 900 001 при `dailyLimit` 15 000 000 |
| `amount_vs_monthly` | below, equal, above | использовано за месяц: как за день / 299 900 000 / 299 900 001 при `monthlyLimit` 300 000 000 |
| `amount_vs_balance` | below, equal, above | `availableBalance`: 100 000 000 / 100 000 / 99 999 |
| `bin` | 400000, 400002, 400004 | BIN карты |
| `bin_lookup` | available, unavailable | Bin Lookup запущен / остановлен |

Ограничения:

1. Если статус не ACTIVE, лимиты и баланс не варьируются (`below`): авторизация останавливается на проверке статуса.
2. Если срок истёк, баланс не варьируется (`below`): авторизация останавливается на проверке срока.
3. Карта со статусом EXPIRED не может иметь действующий срок.

**Сочетания, добавленные вручную.** Покрытие пар не гарантирует покрытия критичных сочетаний трёх и более параметров. В наборе нет строки, где у активной карты с действующим сроком все три суммы ниже лимитов и баланса, то есть нет основного успешного сценария: он покрыт TC-AUTH-EC-01. Отказы `14` и `05` не выражаются параметрами модели и покрыты TC-AUTH-EC-02…04.

### Card-Management

Модель: `pict/model-card-management.txt`, 6 параметров, сценарий — создание карты. Полный перебор: 3⁶ = 729 комбинаций, с учётом ограничения — 648; попарный набор — **15 строк** (`pict/cases-card-management.txt`).

| Параметр | Значения | Конкретные данные |
|---|---|---|
| `bin` | 400000, 400002, 400004 | BIN из генератора |
| `cardholder_name` | short, typical, max_length | `A`, `IVAN IVANOV`, 255 символов |
| `currency_code` | 643, 840, 978 | ISO 4217 |
| `daily_limit` | min, default, max | 100 / 15 000 000 / 30 000 000 |
| `monthly_limit` | low, default, high | 15 000 000 / 300 000 000 / 900 000 000 |
| `initial_balance` | zero, default, high | 0 / 100 000 000 / 5 000 000 000 |

Ограничение: месячный лимит не может быть меньше дневного, поэтому `daily_limit` max (30 000 000) не сочетается с `monthly_limit` low (15 000 000). Сочетание default/low (15 000 000 = 15 000 000) допустимо и проверяет равенство лимитов.

---

## Тест-кейсы

### По классам эквивалентности и граничным значениям

Authorization: запрос `POST /api/internal/authorize` с PAN карты и amount. После ответа баланс проверяется через `GET /api/cards/{pan}`, использование лимитов — в `limit_usage`.

| ID | Требование | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|
| TC-AUTH-EC-01 | AUTH-08 | позитивный | Карта ACTIVE, данные по умолчанию | Авторизация на 100 000 | APPROVED, `00`; баланс 99 900 000; `daily_amount` 100 000; RRN — 12 цифр; authCode — 6 символов `[A-Z0-9]` | КЭ: все валидные классы |
| TC-AUTH-EC-02 | AUTH-01 | негативный | PAN с корректной суммой Луна не создан | Авторизация на 100 000 | DECLINED, `14` | КЭ: карта не существует |
| TC-AUTH-EC-03 | AUTH-01 | негативный | Карта удалена через `DELETE` | Авторизация на 100 000 | DECLINED, `14` | КЭ: карта DELETED |
| TC-AUTH-EC-04 | AUTH-02 | негативный | Карта ACTIVE; CMS остановлен | Авторизация на 100 000 | DECLINED, `05`, ISSUER_TIMEOUT | КЭ: CMS недоступен |
| TC-AUTH-EC-05 | AUTH-10 | позитивный | Карта ACTIVE; Bin Lookup остановлен | Авторизация на 100 000 | APPROVED, `00` | КЭ: Bin Lookup недоступен |
| TC-AUTH-EC-06 | AUTH-03 | негативный | Карта INACTIVE | Авторизация на 100 000 | DECLINED, CARD_INACTIVE; баланс не изменён | КЭ: INACTIVE |
| TC-AUTH-EC-07 | AUTH-03 | негативный | Карта BLOCKED | Авторизация на 100 000 | DECLINED, CARD_BLOCKED; баланс не изменён | КЭ: BLOCKED |
| TC-AUTH-EC-08 | AUTH-03 | негативный | Карта EXPIRED, `expiryDate` `0826` | Авторизация на 100 000 | DECLINED, `54` | КЭ: EXPIRED |
| TC-AUTH-EC-09 | AUTH-04 | негативный | Карта ACTIVE, `expiryDate` `0126` | Авторизация на 100 000 | DECLINED, `54` | КЭ: срок истёк |
| TC-AUTH-EC-10 | AUTH-05 | негативный | Карта ACTIVE, использовано за день 14 950 000 | Авторизация на 100 000 | DECLINED, `61`; `limit_usage` не изменён | КЭ: дневной лимит превышен |
| TC-AUTH-EC-11 | AUTH-06 | негативный | Карта ACTIVE, 299 950 000 за прошлые дни месяца | Авторизация на 100 000 | DECLINED, `61` | КЭ: месячный лимит превышен |
| TC-AUTH-EC-12 | AUTH-07 | негативный | Карта ACTIVE, баланс 50 000 | Авторизация на 100 000 | DECLINED, `51`; баланс 50 000 | КЭ: баланс недостаточен |
| TC-AUTH-EC-13 | AUTH-09 | позитивный | Две карты ACTIVE | Отправить две авторизации одновременно | Оба APPROVED; RRN различаются | КЭ: одна секунда |
| TC-AUTH-BV-01 | AUTH-05 | позитивный | Карта ACTIVE, использовано за день 14 900 000 | Авторизация на 99 999 | APPROVED, `00` | Граница `dailyLimit`, ниже |
| TC-AUTH-BV-02 | AUTH-05 | позитивный | То же | Авторизация на 100 000 | APPROVED, `00` | Граница `dailyLimit`, ON |
| TC-AUTH-BV-03 | AUTH-05 | негативный | То же | Авторизация на 100 001 | DECLINED, `61` | Граница `dailyLimit`, OFF |
| TC-AUTH-BV-04 | AUTH-06 | позитивный | Карта ACTIVE, 299 900 000 за прошлые дни месяца | Авторизация на 99 999 | APPROVED, `00` | Граница `monthlyLimit`, ниже |
| TC-AUTH-BV-05 | AUTH-06 | позитивный | То же | Авторизация на 100 000 | APPROVED, `00` | Граница `monthlyLimit`, ON |
| TC-AUTH-BV-06 | AUTH-06 | негативный | То же | Авторизация на 100 001 | DECLINED, `61` | Граница `monthlyLimit`, OFF |
| TC-AUTH-BV-07 | AUTH-07 | позитивный | Карта ACTIVE, баланс 100 000 | Авторизация на 99 999 | APPROVED, `00`; баланс 1 | Граница баланса, ниже |
| TC-AUTH-BV-08 | AUTH-07 | позитивный | То же | Авторизация на 100 000 | APPROVED, `00`; баланс 0 | Граница баланса, ON |
| TC-AUTH-BV-09 | AUTH-07 | негативный | То же | Авторизация на 100 001 | DECLINED, `51` | Граница баланса, OFF |
| TC-AUTH-BV-10 | AUTH-04 | негативный | Карта ACTIVE, `expiryDate` `0826` | Авторизация на 100 000 | DECLINED, `54` | Граница `expiryDate`, ниже |
| TC-AUTH-BV-11 | AUTH-04 | позитивный | Карта ACTIVE, `expiryDate` `0926` | Авторизация на 100 000 | APPROVED, `00` | Граница `expiryDate`, ON |
| TC-AUTH-BV-12 | AUTH-04 | позитивный | Карта ACTIVE, `expiryDate` `1026` | Авторизация на 100 000 | APPROVED, `00` | Граница `expiryDate`, выше |
| TC-CMS-EC-01 | CMS-01…03 | позитивный | — | `POST /api/cards` с данными по умолчанию; `GET /api/cards/{pan}` | `201`; PAN — 16 цифр, начинается с `400000`, проходит Луна; `expiryDate` `0929`; ACTIVE; поля сохранены | КЭ: все валидные классы |
| TC-CMS-EC-02 | CMS-01 | негативный | — | `POST /api/cards` с `bin` `40000A` | ⚠ `400`, карта не создана | КЭ: `bin` не 6 цифр |
| TC-CMS-EC-03 | CMS-03 | негативный | — | `POST /api/cards` с пустым `cardholderName` | ⚠ `400`, карта не создана | КЭ: пустое имя |
| TC-CMS-EC-04 | CMS-03 | негативный | — | `POST /api/cards` с `dailyLimit` −100 | ⚠ `400`, карта не создана | КЭ: `dailyLimit` ≤ 0 |
| TC-CMS-EC-05 | CMS-03 | негативный | — | `POST /api/cards` с `monthlyLimit` 10 000 000 | ⚠ `400`, карта не создана | КЭ: `monthlyLimit` < `dailyLimit` |
| TC-CMS-EC-06 | CMS-03 | негативный | — | `POST /api/cards` с `initialBalance` −100 | ⚠ `400`, карта не создана | КЭ: баланс < 0 |
| TC-CMS-EC-07 | CMS-04 | позитивный | Карта создана | `GET /api/cards/{pan}` | `200`, данные карты | КЭ: карта существует |
| TC-CMS-EC-08 | CMS-04 | негативный | PAN с верной суммой Луна не создан | `GET /api/cards/{pan}` | `404` | КЭ: карта не существует |
| TC-CMS-EC-09 | CMS-04 | негативный | Карта создана | `GET` по PAN с изменённой последней цифрой | `404` | КЭ: PAN некорректен по Луну |
| TC-CMS-EC-10 | CMS-04, CMS-07 | негативный | Карта удалена | `GET /api/cards/{pan}` | `404` | КЭ: карта DELETED |
| TC-CMS-EC-11 | CMS-05 | позитивный | В БД больше 50 карт | `GET /api/cards` | `200`; 50 карт; `total` = число неудалённых карт | КЭ: без параметров |
| TC-CMS-EC-12 | CMS-05 | позитивный | Есть карты разных статусов | `GET /api/cards?status=BLOCKED` | только BLOCKED | КЭ: фильтр `status` |
| TC-CMS-EC-13 | CMS-05 | позитивный | Есть карты разных BIN | `GET /api/cards?bin=400002` | только PAN с `400002` | КЭ: фильтр `bin` |
| TC-CMS-EC-14 | CMS-05 | позитивный | Известен `total` | `GET /api/cards?offset={total}` | `cards` пуст, `total` не изменён | КЭ: `offset` ≥ `total` |
| TC-CMS-EC-15 | CMS-06 | позитивный | Карта ACTIVE | `PATCH {"status":"BLOCKED"}`; `GET` | `200`; статус BLOCKED; лимиты и баланс не изменены | КЭ: одно поле |
| TC-CMS-EC-16 | CMS-06 | негативный | PAN не создан | `PATCH {"status":"BLOCKED"}` | `404` | КЭ: карта не существует |
| TC-CMS-EC-17 | CMS-07 | позитивный | Карта создана | `DELETE`; `GET /{pan}`; `GET /api/cards` | статус DELETED в БД; `404`; карты нет в списке | КЭ: удаление |
| TC-CMS-EC-18 | CMS-08 | позитивный | — | `POST /api/cards/generate` с `count` 100 и 5 BIN | 100 карт, по 20 на BIN; баланс, лимиты и статусы по CMS-08; месячный = дневной × 30 | КЭ: генерация |
| TC-CMS-EC-19 | CMS-09 | позитивный | Баланс 100 000 000 | `POST /reserve` на 150 000 | `200`; баланс 99 850 000 | КЭ: amount ≤ баланс |
| TC-CMS-EC-20 | CMS-09 | негативный | Баланс 100 000 | `POST /reserve` на 150 000 | ⚠ `400`; баланс 100 000 | КЭ: amount > баланс |
| TC-CMS-BV-01 | CMS-04 | негативный | Карта создана | `GET` по первым 15 цифрам PAN | `404` | Длина PAN, ниже |
| TC-CMS-BV-02 | CMS-04 | позитивный | То же | `GET` по PAN | `200` | Длина PAN, ON |
| TC-CMS-BV-03 | CMS-04 | негативный | То же | `GET` по PAN + `0` | `404` | Длина PAN, OFF |
| TC-CMS-BV-04 | CMS-01 | негативный | — | `POST /api/cards` с `bin` `40000` | ⚠ `400` | Длина `bin`, ниже |
| TC-CMS-BV-05 | CMS-01 | позитивный | — | `POST /api/cards` с `bin` `400000` | `201`; PAN 16 цифр | Длина `bin`, ON |
| TC-CMS-BV-06 | CMS-01 | негативный | — | `POST /api/cards` с `bin` `4000000` | ⚠ `400` | Длина `bin`, OFF |
| TC-CMS-BV-07 | CMS-03 | позитивный | — | `POST /api/cards` с именем из 254 символов | `201`, имя сохранено | Длина имени, ниже |
| TC-CMS-BV-08 | CMS-03 | позитивный | — | То же, 255 символов | `201`, имя сохранено | Длина имени, ON |
| TC-CMS-BV-09 | CMS-03 | негативный | — | То же, 256 символов | ⚠ `400`, карта не создана | Длина имени, OFF |
| TC-CMS-BV-10 | CMS-02 | позитивный | Дата прогона 09.2026 | `POST /api/cards` | `expiryDate` ровно `0929` (не `0829`, `1029`, `0928`, `0930`) | Граница `expiryDate` |
| TC-CMS-BV-11 | CMS-09 | позитивный | Баланс 100 000 | `POST /reserve` на 99 999 | `200`; баланс 1 | Граница резерва, ниже |
| TC-CMS-BV-12 | CMS-09 | позитивный | То же | `POST /reserve` на 100 000 | `200`; баланс 0 | Граница резерва, ON |
| TC-CMS-BV-13 | CMS-09 | негативный | То же | `POST /reserve` на 100 001 | ⚠ `400`; баланс 100 000 | Граница резерва, OFF |

### По попарному набору Authorization

Каждая строка `pict/cases.txt` — отдельный тест-кейс. Источник — номер строки набора.

- **Предусловие:** карта создана с BIN строки; статус, `expiryDate`, баланс и использованные лимиты выставлены по таблице параметров; Bin Lookup запущен или остановлен по строке.
- **Шаги:** `POST /api/internal/authorize` на 100 000; затем `GET /api/cards/{pan}` и проверка `limit_usage`.

| ID | Стр. | card_status | expiry | daily | monthly | balance | bin | bin_lookup | Вид | Требование | Ожидаемый результат |
|---|---:|---|---|---|---|---|---|---|---|---|---|
| TC-AUTH-PW-01 | 1 | INACTIVE | expired | below | below | below | 400002 | unavailable | негативный | AUTH-03, AUTH-10 | DECLINED, CARD_INACTIVE; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-02 | 2 | BLOCKED | current_month | below | below | below | 400002 | available | негативный | AUTH-03 | DECLINED, CARD_BLOCKED; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-03 | 3 | INACTIVE | valid | below | below | below | 400000 | available | негативный | AUTH-03 | DECLINED, CARD_INACTIVE; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-04 | 4 | ACTIVE | valid | equal | equal | above | 400004 | unavailable | негативный | AUTH-07, AUTH-10 | DECLINED, `51`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-05 | 5 | INACTIVE | current_month | below | below | below | 400004 | available | негативный | AUTH-03 | DECLINED, CARD_INACTIVE; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-06 | 6 | ACTIVE | current_month | above | above | above | 400000 | available | негативный | AUTH-05 | DECLINED, `61`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-07 | 7 | ACTIVE | valid | above | above | equal | 400002 | unavailable | негативный | AUTH-05, AUTH-10 | DECLINED, `61`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-08 | 8 | ACTIVE | current_month | below | above | equal | 400000 | unavailable | негативный | AUTH-06, AUTH-10 | DECLINED, `61`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-09 | 9 | ACTIVE | valid | above | below | equal | 400004 | available | негативный | AUTH-05 | DECLINED, `61`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-10 | 10 | EXPIRED | expired | below | below | below | 400004 | available | негативный | AUTH-03 | DECLINED, `54`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-11 | 11 | ACTIVE | current_month | equal | below | above | 400002 | unavailable | негативный | AUTH-07, AUTH-10 | DECLINED, `51`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-12 | 12 | ACTIVE | current_month | below | equal | above | 400000 | unavailable | негативный | AUTH-07, AUTH-10 | DECLINED, `51`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-13 | 13 | BLOCKED | expired | below | below | below | 400004 | unavailable | негативный | AUTH-03, AUTH-10 | DECLINED, CARD_BLOCKED; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-14 | 14 | ACTIVE | expired | above | equal | below | 400000 | available | негативный | AUTH-04 | DECLINED, `54`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-15 | 15 | BLOCKED | valid | below | below | below | 400000 | available | негативный | AUTH-03 | DECLINED, CARD_BLOCKED; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-16 | 16 | ACTIVE | valid | equal | equal | equal | 400002 | unavailable | позитивный | AUTH-08, AUTH-10 | APPROVED, `00`; баланс и `limit_usage` изменены на amount; RRN и authCode по формату |
| TC-AUTH-PW-17 | 17 | EXPIRED | expired | below | below | below | 400002 | unavailable | негативный | AUTH-03, AUTH-10 | DECLINED, `54`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-18 | 18 | EXPIRED | expired | below | below | below | 400000 | available | негативный | AUTH-03 | DECLINED, `54`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-19 | 19 | ACTIVE | expired | equal | above | below | 400000 | available | негативный | AUTH-04 | DECLINED, `54`; баланс и `limit_usage` не изменены |
| TC-AUTH-PW-20 | 20 | ACTIVE | valid | equal | above | above | 400004 | unavailable | негативный | AUTH-06, AUTH-10 | DECLINED, `61`; баланс и `limit_usage` не изменены |
### По попарному набору Card-Management

Каждая строка `pict/cases-card-management.txt` — отдельный тест-кейс. Источник — номер строки набора.

- **Предусловие:** дата прогона 09.2026.
- **Шаги:** `POST /api/cards` с данными по таблице параметров; затем `GET /api/cards/{pan}`.

| ID | Стр. | bin | cardholder_name | currency_code | daily_limit | monthly_limit | initial_balance | Вид | Требование | Ожидаемый результат |
|---|---:|---|---|---|---|---|---|---|---|---|
| TC-CMS-PW-01 | 1 | 400002 | max_length | 643 | min | low | high | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400002`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-02 | 2 | 400000 | short | 978 | max | default | zero | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400000`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-03 | 3 | 400000 | typical | 840 | min | high | default | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400000`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-04 | 4 | 400002 | typical | 643 | max | default | default | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400002`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-05 | 5 | 400000 | max_length | 643 | default | high | zero | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400000`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-06 | 6 | 400004 | max_length | 978 | default | low | default | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400004`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-07 | 7 | 400004 | short | 978 | min | high | high | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400004`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-08 | 8 | 400002 | typical | 978 | default | low | zero | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400002`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-09 | 9 | 400004 | max_length | 840 | max | default | high | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400004`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-10 | 10 | 400002 | short | 840 | default | default | high | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400002`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-11 | 11 | 400000 | typical | 840 | default | low | high | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400000`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-12 | 12 | 400004 | typical | 840 | max | high | zero | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400004`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-13 | 13 | 400004 | short | 643 | default | low | default | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400004`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-14 | 14 | 400004 | typical | 840 | min | default | zero | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400004`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |
| TC-CMS-PW-15 | 15 | 400002 | typical | 978 | min | high | zero | позитивный | CMS-01…03 | `201`; PAN — 16 цифр, начинается с `400002`, проходит Луна; `expiryDate` = `0929`; статус ACTIVE; поля и `availableBalance` = `initialBalance` сохранены |