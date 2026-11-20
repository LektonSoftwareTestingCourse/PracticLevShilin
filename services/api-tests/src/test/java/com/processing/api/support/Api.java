package com.processing.api.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/** HTTP only: no JDBC, queue clients or shared mutable REST-assured globals. */
public final class Api {
    private Api() {
    }

    public static RequestSpecification spec(String service) {
        int port = switch (service) {
            case "cards" -> 8081;
            case "switch" -> 8082;
            case "auth" -> 8083;
            case "merchant" -> 8084;
            case "terminal" -> 8085;
            case "logger" -> 8088;
            case "bin" -> 8096;
            case "notification" -> 8097;
            case "faultgateway" -> 18080;
            case "unavailable" -> 18090;
            case "authfault" -> 18083;
            case "authreadonly" -> 18084;
            case "loggerreadonly" -> 18088;
            default -> 8080;
        };
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty("api." + service, "http://localhost:" + port))
                .setContentType(ContentType.JSON).setAccept(ContentType.JSON)
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 30000)))
                .build();
    }

    public static Response post(String service, String path, Object body) {
        return given().spec(spec(service)).body(body).when().post(path);
    }

    public static Map<String, Object> card() {
        return post("gateway", "/api/cards", Data.card()).then().statusCode(201)
                .body("pan", matchesPattern("400000[0-9]{10}"))
                .body("availableBalance", equalTo(1000000))
                .extract().jsonPath().getMap("");
    }

    public static Response search(String pan) {
        return given().spec(spec("gateway")).queryParam("pan", pan)
                .when().get("/api/transactions/search");
    }

    public static void stored(String pan, String stan) {
        // Outbox/consumer normally completes within a second; 15s bounds slow CI startup.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                search(pan).then().statusCode(200)
                        .body("transactions.findAll { it.stan == '" + stan + "' }", hasSize(1)));
    }

    public static void error(Response response, int status, String service, String message) {
        response.then().statusCode(status).contentType(ContentType.JSON)
                .body("serviceName", equalTo(service)).body("message", containsString(message))
                .body("error", not(isEmptyOrNullString())).body("timestamp", notNullValue());
    }
}
