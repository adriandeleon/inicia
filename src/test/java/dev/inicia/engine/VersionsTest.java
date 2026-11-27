package dev.inicia.engine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @ParameterizedTest
    @CsvSource({
            "3.0.21, 3.0.21.0, 0",
            "26.06, 26.6, 0",
            "26.06, 26.10, -1",
            "8.9.9.1, 8.9.10, -1",
            "0.85, 0.85.0.0, 0",
            "0.84, 0.85, -1",
            "10.0, 9.9, 1",
            "1.0-beta, 1.0.1, -1",
            "2.45.2.windows.1, 2.45.2, 1",
            "2.45.2.windows.1, 2.45.3, -1",
            "1.0-beta, 1.0, 0",
            "'', 1.0, -1",
    })
    void compares(String a, String b, int expected) {
        assertThat(Integer.signum(Versions.compare(a, b))).isEqualTo(expected);
        assertThat(Integer.signum(Versions.compare(b, a))).isEqualTo(-expected);
    }
}
