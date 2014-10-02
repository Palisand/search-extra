package org.wikimedia.search.extra.regex.ngram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.State;
import org.apache.lucene.util.automaton.Transition;
import org.elasticsearch.common.collect.ImmutableSet;
import org.wikimedia.search.extra.regex.expression.And;
import org.wikimedia.search.extra.regex.expression.Expression;
import org.wikimedia.search.extra.regex.expression.ExpressionSource;
import org.wikimedia.search.extra.regex.expression.Leaf;
import org.wikimedia.search.extra.regex.expression.Or;
import org.wikimedia.search.extra.regex.expression.True;

/**
 * A finite automaton who's transitions are ngrams that must be in the string or
 * ngrams we can't check for. Unlikely to be thread safe.
 */
public class NGramAutomaton {
    private final State[] source;
    private final int gramSize;
    private final int maxExpand;
    private final int maxStatesTraced;
    private final List<NGramState> initialStates = new ArrayList<>();
    private final List<NGramState> acceptStates = new ArrayList<>();
    private final Map<NGramState, NGramState> states = new HashMap<>();

    public NGramAutomaton(Automaton source, int gramSize, int maxExpand, int maxStatesTraced) {
        this.source = source.getNumberedStates();
        this.gramSize = gramSize;
        this.maxExpand = maxExpand;
        this.maxStatesTraced = maxStatesTraced;
        // Build the initial states using the first gramSize transitions
        int[] codePoints = new int[gramSize - 1];
        buildInitial(codePoints, 0, source.getInitialState());
        traceRemainingStates();
        removeCycles();
    }

    /**
     * Returns <a href="http://www.research.att.com/sw/tools/graphviz/"
     * target="_top">Graphviz Dot</a> representation of this automaton.
     */
    public String toDot() {
        StringBuilder b = new StringBuilder("digraph Automaton {\n");
        b.append("  rankdir = LR;\n");
        b.append("  initial [shape=plaintext,label=\"\"];\n");
        for (NGramState state : states.keySet()) {
            b.append("  ").append(state.dotName());
            if (source[state.sourceState].isAccept()) {
                b.append(" [shape=doublecircle,label=\"").append(state).append("\"];\n");
            } else {
                b.append(" [shape=circle,label=\"").append(state).append("\"];\n");
            }
            if (state.initial) {
                b.append("  initial -> ").append(state.dotName()).append("\n");
            }
            for (NGramTransition transition : state.outgoingTransitions) {
                b.append("  ").append(transition).append("\n");
            }
        }
        return b.append("}\n").toString();
    }

    /**
     * Convert this automaton into an expression of ngrams that must be found
     * for the entire automaton to match. The automaton isn't simplified so you
     * probably should call {@link Expression#simplify()} on it.
     */
    public Expression<String> expression() {
        return Or.fromExpressionSources(acceptStates);
    }

    /**
     * Recursively walk transitions building the prefixes for the initial state.
     *
     * @param initialStatesQueue repository for new initial states discovered
     *            during the walk
     * @param codePoints work array holding codePoints
     * @param offset offset into work array/depth in tree
     * @param current current source state
     * @return true to continue, false if we hit a dead end
     */
    private boolean buildInitial(int[] codePoints, int offset, State current) {
        if (current.isAccept()) {
            // Hit an accept state before finishing a trigram - meaning you
            // could match this without using any of the trigrams we might find
            // later. In that case we just give up.
            initialStates.clear();
            states.clear();
            return false;
        }
        if (offset == gramSize - 1) {
            // We've walked deeply enough to find an initial state.
            NGramState state = new NGramState(current.getNumber(), new String(codePoints, 0, gramSize - 1), true);
            // Only add one copy of each state - if we've already seen this
            // state just ignore it.
            if (states.containsKey(state)) {
                return true;
            }
            initialStates.add(state);
            states.put(state, state);
            return true;
        }
        for (Transition transition : current.getTransitions()) {
            int min = transition.getMin();
            int max = transition.getMax();
            if (max - min >= maxExpand) {
                // Consider this transition useless.
                max = 0;
                min = 0;
            }
            for (int c = min; c <= max; c++) {
                codePoints[offset] = c;
                if (!buildInitial(codePoints, offset + 1, transition.getDest())) {
                    return false;
                }
            }
        }
        return true;
    }

    private void traceRemainingStates() {
        LinkedList<NGramState> leftToProcess = new LinkedList<NGramState>();
        leftToProcess.addAll(initialStates);
        int[] codePoint = new int[1];
        int statesTraced = 0;
        while (!leftToProcess.isEmpty()) {
            if (statesTraced >= maxStatesTraced) {
                throw new AutomatonTooComplexException();
            }
            statesTraced++;
            NGramState from = leftToProcess.pop();
            if (source[from.sourceState].isAccept()) {
                // Any transitions out of accept states aren't interesting for
                // finding required ngrams
                continue;
            }
            for (Transition transition : source[from.sourceState].getTransitions()) {
                int min = transition.getMin();
                int max = transition.getMax();
                if (max - min >= maxExpand) {
                    // Consider this transition useless.
                    max = 0;
                    min = 0;
                }
                for (int c = min; c <= max; c++) {
                    codePoint[0] = c;
                    String ngram = from.prefix + new String(codePoint, 0, 1);
                    NGramState next = buildOrFind(leftToProcess, transition.getDest().getNumber(), ngram.substring(1));
                    // Transitions containing an invalid character contain no
                    // prefix.
                    if (ngram.indexOf(0) >= 0) {
                        ngram = null;
                    }
                    NGramTransition ngramTransition = new NGramTransition(from, next, ngram);
                    from.outgoingTransitions.add(ngramTransition);
                }
            }
        }
    }

    /**
     * Depth first traversal to break cycles and build the incomingTransitions
     * list. Note that this snaps the cycle off at the last position and doesn't
     * add the backwards transition - it doesn't eat all the (potentially)
     * useless states. The reason we need to remove cycles is because the code
     * that converts the automata into an expression tree doesn't detect cycles.
     */
    private void removeCycles() {
        // Walks each state twice - once marking it and pushing its outgoing
        // states and pushing itself. When it pops a marked state it knows its
        // done with all its children so it can be unmarked.

        // TODO I'm not sure the this it the right way to go at all - it just
        // feels wrong even if it comes up with the right answer.

        // Has this state been seen from this state?
        Set<NGramState> added = new HashSet<>();
        int statesTraced = 0;
        for (NGramState initial : initialStates) {
            LinkedList<NGramState> leftToProcess = new LinkedList<NGramState>();
            leftToProcess.push(initial);
            while (!leftToProcess.isEmpty()) {
                if (statesTraced >= maxStatesTraced) {
                    throw new AutomatonTooComplexException();
                }
                statesTraced++;
                NGramState state = leftToProcess.pop();
                if (state.marked) {
                    state.marked = false;
                    continue;
                }
                state.marked = true;
                leftToProcess.push(state);
                Iterator<NGramTransition> outgoingItr = state.outgoingTransitions.iterator();
                while (outgoingItr.hasNext()) {
                    NGramTransition transition = outgoingItr.next();
                    if (transition.to.marked) {
                        outgoingItr.remove();
                        continue;
                    }
                    if (!added.contains(transition.to)) {
                        leftToProcess.push(transition.to);
                        added.add(transition.to);
                    }
                    transition.to.incomingTransitions.add(transition);
                }
                added.clear();
            }
        }
    }

    private NGramState buildOrFind(LinkedList<NGramState> leftToProcess, int sourceState, String prefix) {
        NGramState built = new NGramState(sourceState, prefix, false);
        NGramState found = states.get(built);
        if (found != null) {
            return found;
        }
        if (source[sourceState].isAccept()) {
            acceptStates.add(built);
        }
        states.put(built, built);
        leftToProcess.add(built);
        return built;
    }

    /**
     * State in the ngram graph. Equals and hashcode only use the sourceState
     * and prefix.
     */
    private static class NGramState implements ExpressionSource<String> {
        /**
         * We use the 0 char to stand in for code points we can't match.
         */
        private static final String INVALID_CHAR = new String(new int[] { 0 }, 0, 1);
        /**
         * We print code points we can't match as double underscores.
         */
        private static final String INVALID_PRINT_CHAR = "__";

        /**
         * State in the source automaton.
         */
        private final int sourceState;
        /**
         * Prefix of the ngram transitions that come from this state.
         */
        private final String prefix;
        /**
         * Is this an initial state? Initial states are potential starts of the
         * regex and thus all incoming transitions are not required.
         */
        private final boolean initial;
        /**
         * Transitions leading from this state.
         */
        private final List<NGramTransition> outgoingTransitions = new ArrayList<>();
        /**
         * Transitions coming into this state.
         */
        private final List<NGramTransition> incomingTransitions = new ArrayList<>();
        /**
         * Lazily initialized expression matching all strings incoming to this
         * state.
         */
        private Expression<String> expression;

        private boolean marked = false;

        private NGramState(int sourceState, String prefix, boolean initial) {
            this.sourceState = sourceState;
            this.prefix = prefix;
            this.initial = initial;
        }

        public String toString() {
            return "(" + prettyPrefix() + ", " + sourceState + ")";
        }

        public String dotName() {
            // Spaces become ___ because __ was taken by null.
            return prettyPrefix().replace(" ", "___") + sourceState;
        }

        public String prettyPrefix() {
            return prefix.replace(INVALID_CHAR, INVALID_PRINT_CHAR);
        }

        @Override
        public Expression<String> expression() {
            if (expression == null) {
                if (initial) {
                    expression = True.instance();
                } else {
                    expression = Or.fromExpressionSources(incomingTransitions);
                }
            }
            return expression;
        }

        // Equals and hashcode from Eclipse.
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((prefix == null) ? 0 : prefix.hashCode());
            result = prime * result + sourceState;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NGramState other = (NGramState) obj;
            if (prefix == null) {
                if (other.prefix != null)
                    return false;
            } else if (!prefix.equals(other.prefix))
                return false;
            if (sourceState != other.sourceState)
                return false;
            return true;
        }
    }

    private static class NGramTransition implements ExpressionSource<String> {
        private final NGramState from;
        private final NGramState to;
        private final String ngram;

        private NGramTransition(NGramState from, NGramState to, String ngram) {
            this.from = from;
            this.to = to;
            this.ngram = ngram;
        }

        @Override
        public Expression<String> expression() {
            if (ngram == null) {
                return from.expression();
            }
            return new And<String>(ImmutableSet.of(from.expression(), new Leaf<String>(ngram)));
        }

        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append(from.dotName()).append(" -> ").append(to.dotName());
            if (ngram != null) {
                b.append(" [label=\"" + ngram.replace(" ", "_") + "\"]");
            }
            return b.toString();
        }
    }
}
