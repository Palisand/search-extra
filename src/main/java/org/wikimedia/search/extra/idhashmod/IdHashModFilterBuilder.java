package org.wikimedia.search.extra.idhashmod;

import java.io.IOException;
import java.util.Locale;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.BaseFilterBuilder;

/**
 * Builds the {@link IdHashModFilter}.
 */
public class IdHashModFilterBuilder extends BaseFilterBuilder {
    private final int mod;
    private final int match;

    public IdHashModFilterBuilder(int mod, int match) {
        if (match >= mod) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "If match is >= mod it won't find anything. match = %s and mod = %s", match, mod));
        }
        this.mod = mod;
        this.match = match;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(IdHashModFilterParser.NAMES[0]);
        builder.field("mod", mod);
        builder.field("match", match);
        builder.endObject();
    }
}
