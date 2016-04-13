package org.wikimedia.search.extra.safer.phrase;


import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Adapts PhraseQuery like queries to return what safer_query_string needs from
 * them.
 */
public abstract class PhraseQueryAdapter {
    /**
     * The number of positions in the phrase query. Client code expects this to
     * be fast so don't go allocating memory on every call. Capisce?
     *
     * @return the number of positions in the phrase query
     */
    public abstract int terms();
    /**
     * @return the original query unwrapped
     */
    public abstract Query unwrap();
    /**
     * @return the wrapper query as term queries
     */
    public abstract Query convertToTermQueries();

    public static PhraseQueryAdapter adapt(PhraseQuery pq) {
        return new PhraseQueryAdapterForPhraseQuery(pq);
    }

    public static PhraseQueryAdapter adapt(MultiPhraseQuery pq) {
        return new PhraseQueryAdapterForMultiPhraseQuery(pq);
    }

    private static final class PhraseQueryAdapterForPhraseQuery extends PhraseQueryAdapter {
        private final PhraseQuery pq;
        private final int terms;

        private PhraseQueryAdapterForPhraseQuery(PhraseQuery pq) {
            this.pq = pq;
            terms = pq.getTerms().length;
        }

        @Override
        public int terms() {
            return terms;
        }

        @Override
        public Query unwrap() {
            return pq;
        }

        @Override
        public Query convertToTermQueries() {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (Term term : pq.getTerms()) {
                builder.add(new TermQuery(term), BooleanClause.Occur.MUST);
            }
            BooleanQuery bq = builder.build();
            // We won't wrap inside a BoostQuery since we are maybe already inside
            // a BoostQuery, we'll just have remove this line when setBoost is removed
            bq.setBoost(pq.getBoost());
            return bq;
        }
    }

    private static final class PhraseQueryAdapterForMultiPhraseQuery extends PhraseQueryAdapter {
        private final MultiPhraseQuery pq;
        private final int totalTerms;

        private PhraseQueryAdapterForMultiPhraseQuery(MultiPhraseQuery pq) {
            this.pq = pq;
            int total = 0;
            for (Term[] terms : pq.getTermArrays()) {
                total += terms.length;
            }
            totalTerms = total;
        }

        @Override
        public int terms() {
            return totalTerms;
        }

        @Override
        public Query unwrap() {
            return pq;
        }

        @Override
        public Query convertToTermQueries() {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (Term[] terms : pq.getTermArrays()) {
                BooleanQuery.Builder inner = new BooleanQuery.Builder();
                for (Term term: terms) {
                    inner.add(new TermQuery(term), BooleanClause.Occur.SHOULD);
                }
                builder.add(inner.build(), BooleanClause.Occur.MUST);
            }
            BooleanQuery bq = builder.build();
            // We won't wrap inside a BoostQuery since we are maybe already inside
            // a BoostQuery, we'll just have remove this line when setBoost is removed
            bq.setBoost(pq.getBoost());
            return bq;
        }
    }
}
