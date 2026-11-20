package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.hamcrest.Matchers.*;

/** Source: TransactionRequestValidator and TransactionValidationFilter. */
class GatewayValidationTest {
    static Stream<Arguments> invalid() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
                "merchantId", "mcc", "acquirerId")) {
            for (Object value : new Object[] {null, "", " "}) {
                cases.add(Arguments.of(field, value, "Field '" + field + "' is required"));
            }
        }
        cases.add(Arguments.of("transmissionDateTime", null, "Field 'transmissionDateTime' is required"));
        cases.add(Arguments.of("amount", null, "Field 'amount' is required"));
        for (int amount : new int[] {-1, 0}) {
            cases.add(Arguments.of("amount", amount, "Field 'amount' must be > 0"));
        }
        for (String value : List.of("010", "01000", "0200", "abcd")) {
            cases.add(Arguments.of("mti", value, "Field 'mti' must be '0100'"));
        }
        for (String value : List.of("4".repeat(15), "4".repeat(17), "A".repeat(16))) {
            cases.add(Arguments.of("pan", value, "Field 'pan' must be exactly 16 digits"));
        }
        for (String value : List.of("64", "6430")) {
            cases.add(Arguments.of("currencyCode", value, "Field 'currencyCode' must be exactly 3 characters"));
        }
        for (String value : List.of("541", "54111", "ABCD")) {
            cases.add(Arguments.of("mcc", value, "Field 'mcc' must be exactly 4 digits"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalid")
    void rejectedField(String field, Object value, String message) {
        // given
        var request = Data.authorization("400000" + Data.digits(10));
        request.put(field, value);
        // when
        var response = Api.post("gateway", "/api/transactions", request);
        // then
        Api.error(response, 400, "gateway", message);
        response.then().body("error", equalTo("VALIDATION_ERROR")).body("message", equalTo(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "[]", "{\"amount\":\"not-a-number\"}",
            "{\"transmissionDateTime\":\"yesterday\"}"})
    void malformedJson(String body) {
        // given / when
        var response = Api.post("gateway", "/api/transactions", body);
        // then
        Api.error(response, 400, "gateway", "Request body must be valid JSON");
    }
}
