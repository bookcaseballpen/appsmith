package com.appsmith.server.catalog;

import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fork: an app's tags are free text. Each tag is trimmed, lower-cased and has its inner runs of white space collapsed
 * to one space; empty tags and repeats are dropped, the first spelling's position is kept. At most {@link #MAX_TAGS}
 * tags of at most {@link #MAX_LENGTH} characters each.
 *
 * <p>The tags endpoint refuses a list over the limits ({@link #validate}); every other write path (the app domain's
 * setter, import, a cab-sync push) clamps instead ({@link #clamp}), as the description does, so an import never fails
 * on its tags.
 */
public final class ApplicationTags {

    public static final int MAX_TAGS = 10;
    public static final int MAX_LENGTH = 30;

    private ApplicationTags() {}

    /** The tags normalised, without applying the limits. Null stays null. */
    public static List<String> normalise(Collection<String> tags) {
        if (tags == null) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalised = normaliseOne(tag);
            if (!normalised.isEmpty()) {
                result.add(normalised);
            }
        }
        return new ArrayList<>(result);
    }

    /** The tags normalised, each cut to {@link #MAX_LENGTH} characters, the first {@link #MAX_TAGS} kept. */
    public static List<String> clamp(Collection<String> tags) {
        if (tags == null) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalised = cut(normaliseOne(tag));
            if (!normalised.isEmpty() && result.size() < MAX_TAGS) {
                result.add(normalised);
            }
        }
        return new ArrayList<>(result);
    }

    /** The tags normalised, or INVALID_PARAMETER when there are more than the limits allow. Null means none. */
    public static List<String> validate(Collection<String> tags) {
        List<String> normalised = normalise(tags == null ? List.of() : tags);
        if (normalised.size() > MAX_TAGS
                || normalised.stream().anyMatch(tag -> tag.codePointCount(0, tag.length()) > MAX_LENGTH)) {
            throw new AppsmithException(
                    AppsmithError.INVALID_PARAMETER,
                    "tags (at most " + MAX_TAGS + " tags of at most " + MAX_LENGTH + " characters each)");
        }
        return normalised;
    }

    /** True when both lists hold the same tags once normalised; null and empty are the same. */
    public static boolean same(Collection<String> a, Collection<String> b) {
        return clamp(a == null ? List.of() : a).equals(clamp(b == null ? List.of() : b));
    }

    private static String normaliseOne(String tag) {
        return tag == null ? "" : tag.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // Counted in code points, so the cut never splits a surrogate pair; strip again in case it ends on a space.
    private static String cut(String tag) {
        if (tag.codePointCount(0, tag.length()) <= MAX_LENGTH) {
            return tag;
        }
        return tag.substring(0, tag.offsetByCodePoints(0, MAX_LENGTH)).strip();
    }
}
