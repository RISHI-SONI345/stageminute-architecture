package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Device ids and IP addresses are stored only as a keyed hash (F-086, 05 F-082). */
class IdentifierHasherTest {

    @Test
    void sameInputAndPepperGiveTheSameHash() {
        IdentifierHasher hasher = new IdentifierHasher("pepper-one-pepper-one-pepper-one!");

        assertThat(hasher.hash("device-1")).isEqualTo(hasher.hash("device-1")).hasSize(32);
    }

    @Test
    void differentInputsGiveDifferentHashes() {
        IdentifierHasher hasher = new IdentifierHasher("pepper-one-pepper-one-pepper-one!");

        assertThat(hasher.hash("device-1")).isNotEqualTo(hasher.hash("device-2"));
    }

    @Test
    void aDifferentPepperGivesADifferentHash() {
        assertThat(new IdentifierHasher("pepper-one-pepper-one-pepper-one!").hash("device-1"))
                .isNotEqualTo(new IdentifierHasher("pepper-two-pepper-two-pepper-two!").hash("device-1"));
    }

    @Test
    void theHashDoesNotContainTheInput() {
        byte[] hash = new IdentifierHasher("pepper-one-pepper-one-pepper-one!").hash("192.168.1.20");

        assertThat(new String(hash, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("192.168");
    }

    @Test
    void aMissingPepperStopsTheServerAndNeverShowsAValue() {
        assertThatThrownBy(() -> new IdentifierHasher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STAGEMINUTE_HASH_PEPPER");
        assertThatThrownBy(() -> new IdentifierHasher("   ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aShortPepperIsRefused() {
        assertThatThrownBy(() -> new IdentifierHasher("short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
