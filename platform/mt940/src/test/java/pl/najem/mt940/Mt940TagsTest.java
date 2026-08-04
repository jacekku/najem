package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940TagsTest {

    @Test
    void splitsFieldsOnTheirTags() {
        List<Mt940Tags.Field> fields = Mt940Tags.split("""
            :20:NAJEM1
            :25:PL61109010140000071219812874
            :28C:1/1
            """);

        assertThat(fields).containsExactly(
            new Mt940Tags.Field("20", "NAJEM1"),
            new Mt940Tags.Field("25", "PL61109010140000071219812874"),
            new Mt940Tags.Field("28C", "1/1"));
    }

    @Test
    void joinsContinuationLinesIntoTheFieldAbove() {
        List<Mt940Tags.Field> fields = Mt940Tags.split("""
            :86:~20NAJEM/M1/2026
            ~32NAJEMCA NAJEM-M1-2026
            :62F:C260901PLN2500,00
            """);

        assertThat(fields).hasSize(2);
        assertThat(fields.getFirst().value()).isEqualTo("~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026");
    }

    @Test
    void toleratesCarriageReturnsAndTheTrailingBlockTerminator() {
        List<Mt940Tags.Field> fields = Mt940Tags.split(":20:NAJEM1\r\n:25:PL61\r\n-\r\n");

        assertThat(fields).containsExactly(
            new Mt940Tags.Field("20", "NAJEM1"),
            new Mt940Tags.Field("25", "PL61"));
    }

    @Test
    void rejectsTextThatDoesNotStartWithATag() {
        assertThatThrownBy(() -> Mt940Tags.split("NAJEM1\n:25:PL61\n"))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("NAJEM1");
    }
}
