package org.wikimedia.search.extra.safer;

import java.io.IOException;
import java.util.Locale;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.BaseQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.wikimedia.search.extra.safer.phrase.PhraseTooLargeAction;

public class SaferQueryBuilder extends BaseQueryBuilder {
    private final QueryBuilder delegate;
    private Integer maxTermsPerPhraseQuery;
    private Integer maxTermsInAllPhraseQueries;
    private PhraseTooLargeAction phraseTooLargeAction;
    public SaferQueryBuilder(QueryBuilder delegate) {
        this.delegate = delegate;
    }

    public SaferQueryBuilder maxTermsPerPhraseQuery(int maxTermsPerPhraseQuery) {
        this.maxTermsPerPhraseQuery = maxTermsPerPhraseQuery;
        return this;
    }

    public SaferQueryBuilder maxTermsInAllPhraseQueries(int maxTermsInAllPhraseQueries) {
        this.maxTermsInAllPhraseQueries = maxTermsInAllPhraseQueries;
        return this;
    }

    public SaferQueryBuilder phraseTooLargeAction(PhraseTooLargeAction phraseTooLargeAction) {
        this.phraseTooLargeAction = phraseTooLargeAction;
        return this;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject("safer");
        builder.rawField("query", delegate.buildAsBytes(builder.contentType()));
        builder.startObject("phrase");
        if (maxTermsPerPhraseQuery != null) {
            builder.field("max_terms_per_query", maxTermsPerPhraseQuery);
        }
        if (maxTermsInAllPhraseQueries != null) {
            builder.field("max_terms_in_all_queries", maxTermsInAllPhraseQueries);
        }
        if (phraseTooLargeAction != null) {
            builder.field("phrase_too_large_action", phraseTooLargeAction.toString().toLowerCase(Locale.ROOT));
        }
        builder.endObject();
        builder.endObject();
    }
}
