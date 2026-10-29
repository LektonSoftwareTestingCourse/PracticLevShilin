# Практика 4 — Unit-тесты

## Запуск

Нужны JDK 21 и Maven. Команды выполняются из корня репозитория.
Docker, PostgreSQL и RabbitMQ для этих тестов не требуются.

~~~powershell
mvn -f services/pom.xml -Pauthorization clean verify
mvn -f services/pom.xml -Pswitch clean verify
mvn -f services/pom.xml -Pcard-management clean verify
mvn -f services/pom.xml -Pgateway clean verify
~~~

Каждая команда собирает также зависимость common. Фаза verify запускает
unit-тесты, Checkstyle, отчёт JaCoCo и для Switch проверку покрытия ветвей ≥ 70%.
Для запуска только тестов можно заменить clean verify на test.

Отчёты каждого сервиса:

- services/{service}/target/surefire-reports/ — результаты тестов;
- services/{service}/target/site/jacoco/index.html — HTML-покрытие;
- services/{service}/target/site/jacoco/jacoco.xml — XML-покрытие.
