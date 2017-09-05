package org.wikimedia.search.extra;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.monitor.os.OsService;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.script.NativeScriptFactory;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.wikimedia.search.extra.analysis.filters.PreserveOriginalFilterFactory;
import org.wikimedia.search.extra.fuzzylike.FuzzyLikeThisQueryBuilder;
import org.wikimedia.search.extra.latency.LatencyStatsAction;
import org.wikimedia.search.extra.latency.RestGetLatencyStats;
import org.wikimedia.search.extra.latency.SearchLatencyListener;
import org.wikimedia.search.extra.latency.TransportLatencyStatsAction;
import org.wikimedia.search.extra.levenshtein.LevenshteinDistanceScoreBuilder;
import org.wikimedia.search.extra.regex.SourceRegexQueryBuilder;
import org.wikimedia.search.extra.router.DegradedRouterQueryBuilder;
import org.wikimedia.search.extra.router.SystemLoad;
import org.wikimedia.search.extra.router.TokenCountRouterQueryBuilder;
import org.wikimedia.search.extra.superdetectnoop.ChangeHandler;
import org.wikimedia.search.extra.superdetectnoop.SetHandler;
import org.wikimedia.search.extra.superdetectnoop.SuperDetectNoopScript;
import org.wikimedia.search.extra.superdetectnoop.VersionedDocumentHandler;
import org.wikimedia.search.extra.superdetectnoop.WithinAbsoluteHandler;
import org.wikimedia.search.extra.superdetectnoop.WithinPercentageHandler;
import org.wikimedia.search.extra.util.Suppliers.MutableSupplier;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;


/**
 * Setup the Elasticsearch plugin.
 */
public class ExtraPlugin extends Plugin implements SearchPlugin, AnalysisPlugin, ScriptPlugin, ActionPlugin {

    private final SearchLatencyListener latencyListener;
    private final MutableSupplier<ThreadPool> threadPoolSupplier;
    private final SystemLoad loadStats;

    public ExtraPlugin(Settings settings) {
        threadPoolSupplier = new MutableSupplier<>();
        latencyListener = new SearchLatencyListener(settings, threadPoolSupplier);
        loadStats = new SystemLoad(latencyListener, new OsService(settings));
    }

    @Override
    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
                                               ResourceWatcherService resourceWatcherService, ScriptService scriptService,
                                               NamedXContentRegistry xContentRegistry) {
        threadPoolSupplier.set(threadPool);
        return Collections.singletonList(latencyListener);
    }

    /**
     * Register our parsers.
     */
    @Override
    @SuppressWarnings("deprecation")
    public List<QuerySpec<?>> getQueries() {
        return Arrays.asList(
                new QuerySpec<>(SourceRegexQueryBuilder.NAME, SourceRegexQueryBuilder::new, SourceRegexQueryBuilder::fromXContent),
                new QuerySpec<>(FuzzyLikeThisQueryBuilder.NAME, FuzzyLikeThisQueryBuilder::new, FuzzyLikeThisQueryBuilder::fromXContent),
                new QuerySpec<>(TokenCountRouterQueryBuilder.NAME, TokenCountRouterQueryBuilder::new, TokenCountRouterQueryBuilder::fromXContent),
                new QuerySpec<>(DegradedRouterQueryBuilder.NAME, (in) -> new DegradedRouterQueryBuilder(in, loadStats), (pc) -> DegradedRouterQueryBuilder.fromXContent(pc, loadStats))
        );
    }

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        Map<String, AnalysisProvider<TokenFilterFactory>> map = new HashMap<>();
        map.put("preserve_original", PreserveOriginalFilterFactory::new);
        map.put("preserve_original_recorder", PreserveOriginalFilterFactory.RecorderFactory::new);
        return Collections.unmodifiableMap(map);
    }

    @Override
    public List<NativeScriptFactory> getNativeScripts() {
        Set<ChangeHandler.Recognizer> recognizers = new HashSet<>(Arrays.asList(
                new ChangeHandler.Equal.Recognizer(),
                new WithinPercentageHandler.Recognizer(),
                new WithinAbsoluteHandler.Recognizer(),
                new SetHandler.Recognizer(),
                new VersionedDocumentHandler.Recognizer()
        ));
        return Collections.singletonList(new SuperDetectNoopScript.Factory(recognizers));
    }

    @Override
    public List<ScoreFunctionSpec<?>> getScoreFunctions() {
        return Collections.singletonList(
            new ScoreFunctionSpec<>(
                    LevenshteinDistanceScoreBuilder.NAME.getPreferredName(),
                    LevenshteinDistanceScoreBuilder::new,
                    LevenshteinDistanceScoreBuilder::fromXContent
            )
        );
    }

    @Override
    public void onIndexModule(IndexModule indexModule) {
        indexModule.addSearchOperationListener(latencyListener);
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return Collections.unmodifiableList(Collections.singletonList(
                new ActionHandler<>(LatencyStatsAction.INSTANCE, TransportLatencyStatsAction.class)
        ));
    }

    @Override
    public List<RestHandler> getRestHandlers(Settings settings, RestController restController,
                                             ClusterSettings clusterSettings, IndexScopedSettings indexScopedSettings,
                                             SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver,
                                             Supplier<DiscoveryNodes> nodesInCluster) {
        return Collections.singletonList(new RestGetLatencyStats(settings, restController));
    }
}
