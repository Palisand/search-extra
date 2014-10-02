package org.wikimedia.search.extra.regex.ngram;

import static org.junit.Assert.assertEquals;
import static org.wikimedia.search.extra.regex.expression.Leaf.leaves;

import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.RegExp;
import org.junit.Test;
import org.wikimedia.search.extra.regex.expression.And;
import org.wikimedia.search.extra.regex.expression.Expression;
import org.wikimedia.search.extra.regex.expression.Leaf;
import org.wikimedia.search.extra.regex.expression.Or;
import org.wikimedia.search.extra.regex.expression.True;
import org.wikimedia.search.extra.regex.ngram.AutomatonTooComplexException;
import org.wikimedia.search.extra.regex.ngram.NGramAutomaton;

public class NGramAutomatonTest {
    @Test
    public void simple() {
        assertTrigramExpression("cat", new Leaf<>("cat"));
    }

    @Test
    public void options() {
        assertTrigramExpression("(cat)|(dog)|(cow)", new Or<String>(leaves("cat", "dog", "cow")));
    }

    @Test
    public void leadingWildcard() {
        assertTrigramExpression(".*cat", new Leaf<>("cat"));
    }

    @Test
    public void followingWildcard() {
        assertTrigramExpression("cat.*", new Leaf<>("cat"));
    }

    @Test
    public void initialCharClassExpanded() {
        assertTrigramExpression("[abcd]oop", new And<String>(new Or<String>(leaves("aoo", "boo", "coo", "doo")), new Leaf<>("oop")));
    }

    @Test
    public void initialCharClassSkipped() {
        assertTrigramExpression("[abcde]oop", new Leaf<>("oop"));
    }

    @Test
    public void followingCharClassExpanded() {
        assertTrigramExpression("oop[abcd]", new And<String>(
                new Leaf<>("oop"),
                new Or<String>(leaves("opa", "opb", "opc", "opd"))));
    }

    @Test
    public void followingCharClassSkipped() {
        assertTrigramExpression("oop[abcde]", new Leaf<>("oop"));
    }

    @Test
    public void shortCircuit() {
        assertTrigramExpression("a|(lopi)", True.<String> instance());
    }

    @Test
    public void optional() {
        assertTrigramExpression("(a|[j-t])lopi", new And<String>(leaves("lop", "opi")));
    }

    @Test
    public void loop() {
        assertTrigramExpression("ab(cdef)*gh", new Or<String>(
                new And<String>(leaves("abc", "bcd", "cde", "def", "efg", "fgh")),
                new And<String>(leaves("abg", "bgh"))));
    }

    @Test
    public void converge() {
        assertTrigramExpression("(ajdef)|(cdef)", new And<String>(
                new Or<String>(
                        new And<String>(leaves("ajd", "jde")),
                        new Leaf<>("cde")),
                        new Leaf<>("def")));
    }

    @Test
    public void complex() {
        assertTrigramExpression("h[efas] te.*me", new And<String>(
                new Or<String>(
                        new And<String>(leaves("ha ", "a t")),
                        new And<String>(leaves("he ", "e t")),
                        new And<String>(leaves("hf ", "f t")),
                        new And<String>(leaves("hs ", "s t"))),
                new Leaf<>(" te")));
    }

    // The pgTrgmExample methods below test examples from the slides at
    // http://www.pgcon.org/2012/schedule/attachments/248_Alexander%20Korotkov%20-%20Index%20support%20for%20regular%20expression%20search.pdf
    @Test
    public void pgTrgmExample1() {
        assertTrigramExpression("a(b+|c+)d", new Or<String>(
                new Leaf<>("abd"),
                new And<String>(leaves("abb", "bbd")),
                new Leaf<>("acd"),
                new And<String>(leaves("acc", "ccd"))));
    }

    @Test
    public void pgTrgmExample2() {
        assertTrigramExpression("(abc|cba)def", new And<String>(
                new Leaf<>("def"), new Or<String>(
                        new And<String>(leaves("abc", "bcd", "cde")),
                        new And<String>(leaves("cba", "bad", "ade")))));
    }

    @Test
    public void pgTrgmExample3() {
        assertTrigramExpression("abc+de", new And<String>(
                new Leaf<>("abc"),
                new Leaf<>("cde"),
                new Or<String>(
                        new Leaf<>("bcd"),
                        new And<String>(leaves("bcc", "ccd")))));
    }

    @Test
    public void pgTrgmExample4() {
        assertTrigramExpression("(abc*)+de", new Or<String>(
                new And<String>(leaves("abd", "bde")),
                new And<String>(
                        new Leaf<>("abc"),
                        new Leaf<>("cde"),
                        new Or<String>(
                                new Leaf<>("bcd"),
                                new And<String>(leaves("bcc", "ccd"))))));
    }

    @Test
    public void pgTrgmExample5() {
        assertTrigramExpression("ab(cd)*ef", new Or<String>(
                new And<String>(leaves("abe", "bef")),
                new And<String>(leaves("abc", "bcd", "cde", "def"))));
    }

    /**
     * Automatons that would take too long to process are aborted.
     */
    @Test(expected=AutomatonTooComplexException.class)
    public void tooManyStates() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            b.append("[efas]+");
        }
        assertTrigramExpression(b.toString(), null /*ignored*/);
    }

    /**
     * Asserts that the provided regex extracts the expected expression when
     * configured to extract trigrams. Uses 4 as maxExpand just because I had to
     * pick something and 4 seemed pretty good.
     */
    private void assertTrigramExpression(String regex, Expression<String> expected) {
        Automaton automaton = new RegExp(regex).toAutomaton();
        // System.err.println(automaton.toDot());
        NGramAutomaton ngramAutomaton = new NGramAutomaton(automaton, 3, 4, 10000);
        // System.err.println(ngramAutomaton.toDot());
        Expression<String> expression = ngramAutomaton.expression();
        // System.err.println(expression);
        expression = expression.simplify();
        // System.err.println(expression);
        assertEquals(expected, expression);
    }
}
