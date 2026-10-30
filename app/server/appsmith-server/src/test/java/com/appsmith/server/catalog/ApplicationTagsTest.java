package com.appsmith.server.catalog;

import com.appsmith.server.domains.Application;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationTagsTest {

    @Test
    void should_trimLowerCaseAndDropRepeats_when_normalising() {
        // When
        List<String> tags =
                ApplicationTags.normalise(Arrays.asList(" Sales ", "SALES", "HR \t Team", "", null, "hr team"));

        // Then
        assertThat(tags).containsExactly("sales", "hr team");
    }

    @Test
    void should_keepFirstTenAndCutLongTags_when_clamping() {
        // Given
        List<String> twelve = IntStream.range(0, 12).mapToObj(i -> "tag" + i).toList();
        String long40 = "a".repeat(29) + " " + "b".repeat(10);

        // When
        List<String> clamped = ApplicationTags.clamp(twelve);
        List<String> cut = ApplicationTags.clamp(List.of(long40));

        // Then
        assertThat(clamped).hasSize(ApplicationTags.MAX_TAGS).startsWith("tag0").endsWith("tag9");
        assertThat(cut).containsExactly("a".repeat(29));
    }

    @Test
    void should_neverSplitSurrogatePair_when_cuttingTag() {
        // Given
        String emoji = "😀";
        String tag = emoji.repeat(ApplicationTags.MAX_LENGTH + 5);

        // When
        String cut = ApplicationTags.clamp(List.of(tag)).get(0);

        // Then
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(ApplicationTags.MAX_LENGTH);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }

    @Test
    void should_refuse_when_validatingMoreThanTenTags() {
        // Given
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "tag" + i).toList();

        // When / Then
        assertThatThrownBy(() -> ApplicationTags.validate(eleven))
                .isInstanceOf(AppsmithException.class)
                .satisfies(error ->
                        assertThat(((AppsmithException) error).getError()).isEqualTo(AppsmithError.INVALID_PARAMETER))
                .hasMessageContaining("at most 10 tags");
    }

    @Test
    void should_refuse_when_validatingTagOverThirtyCharacters() {
        // When / Then
        assertThatThrownBy(() -> ApplicationTags.validate(List.of("x".repeat(31))))
                .isInstanceOf(AppsmithException.class)
                .hasMessageContaining("at most 30 characters");
    }

    @Test
    void should_countRepeatsOnce_when_validating() {
        // Given
        List<String> repeats = IntStream.range(0, 15).mapToObj(i -> "Same").toList();

        // When
        List<String> validated = ApplicationTags.validate(repeats);

        // Then
        assertThat(validated).containsExactly("same");
    }

    @Test
    void should_treatNullAndEmptyAsSame_when_comparing() {
        // When / Then
        assertThat(ApplicationTags.same(null, List.of())).isTrue();
        assertThat(ApplicationTags.same(List.of("A"), List.of(" a "))).isTrue();
        assertThat(ApplicationTags.same(List.of("a", "b"), List.of("b", "a"))).isFalse();
    }

    @Test
    void should_normaliseAndKeepNull_when_settingTagsOnApplication() {
        // Given
        Application application = new Application();

        // When
        application.setTags(null);
        List<String> unset = application.getTags();
        application.setTags(List.of(" Ops ", "ops"));

        // Then
        assertThat(unset).isNull();
        assertThat(application.getTags()).containsExactly("ops");
    }
}
