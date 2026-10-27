package com.ifp.invoice.web;

import com.ifp.invoice.service.ExpectedVersion;
import com.ifp.invoice.service.PreconditionFailedException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strong ETags carrying the optimistic-lock version, e.g. {@code "3"}. */
public final class ETags {

    private static final Pattern STRONG_VERSION_TAG = Pattern.compile("^\"(\\d{1,18})\"$");

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Parses If-Match. Absent or {@code *} imposes no version precondition. If-Match requires strong comparison,
     * so weak tags ({@code W/"3"}), lists and malformed values can never match and fail the precondition.
     */
    public static ExpectedVersion parseIfMatch(String header) {
        if (header == null || header.strip().equals("*")) {
            return ExpectedVersion.ANY;
        }
        Matcher matcher = STRONG_VERSION_TAG.matcher(header.strip());
        if (!matcher.matches()) {
            throw new PreconditionFailedException(null);
        }
        return ExpectedVersion.of(Long.parseLong(matcher.group(1)));
    }
}
