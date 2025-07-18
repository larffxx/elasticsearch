/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.ccq;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.http.HttpHost;
import org.elasticsearch.Version;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.test.MapMatcher;
import org.elasticsearch.test.TestClustersThreadFilter;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.xpack.esql.action.EsqlCapabilities;
import org.elasticsearch.xpack.esql.qa.rest.RequestIndexFilteringTestCase;
import org.elasticsearch.xpack.esql.qa.rest.RestEsqlTestCase;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.ListMatcher.matchesList;
import static org.elasticsearch.test.MapMatcher.assertMap;
import static org.elasticsearch.test.MapMatcher.matchesMap;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@ThreadLeakFilters(filters = TestClustersThreadFilter.class)
public class RequestIndexFilteringIT extends RequestIndexFilteringTestCase {

    static ElasticsearchCluster remoteCluster = Clusters.remoteCluster();
    static ElasticsearchCluster localCluster = Clusters.localCluster(remoteCluster);

    @ClassRule
    public static TestRule clusterRule = RuleChain.outerRule(remoteCluster).around(localCluster);
    private static RestClient remoteClient;

    @Override
    protected String getTestRestCluster() {
        return localCluster.getHttpAddresses();
    }

    @Before
    public void setRemoteClient() throws IOException {
        if (remoteClient == null) {
            var clusterHosts = parseClusterHosts(remoteCluster.getHttpAddresses());
            remoteClient = buildClient(restClientSettings(), clusterHosts.toArray(new HttpHost[0]));
        }
    }

    private boolean isCCSRequest;

    @AfterClass
    public static void closeRemoteClients() throws IOException {
        try {
            IOUtils.close(remoteClient);
        } finally {
            remoteClient = null;
        }
    }

    @Override
    protected void indexTimestampData(int docs, String indexName, String date, String differentiatorFieldName) throws IOException {
        indexTimestampDataForClient(client(), docs, indexName, date, differentiatorFieldName);
        indexTimestampDataForClient(remoteClient, docs, indexName, date, differentiatorFieldName);
    }

    @Override
    protected String from(String... indexName) {
        isCCSRequest = randomBoolean();
        if (isCCSRequest) {
            return "FROM *:" + String.join(",*:", indexName);
        } else {
            return "FROM " + String.join(",", indexName);
        }
    }

    @Override
    public Map<String, Object> runEsql(RestEsqlTestCase.RequestObjectBuilder requestObject) throws IOException {
        return runEsql(requestObject, true);
    }

    @Override
    public Map<String, Object> runEsql(RestEsqlTestCase.RequestObjectBuilder requestObject, boolean checkPartialResults)
        throws IOException {
        if (requestObject.allowPartialResults() != null) {
            assumeTrue(
                "require allow_partial_results on local cluster",
                clusterHasCapability("POST", "/_query", List.of(), List.of("support_partial_results")).orElse(false)
            );
        }
        requestObject.includeCCSMetadata(true);
        return super.runEsql(requestObject, checkPartialResults);
    }

    @After
    public void wipeRemoteTestData() throws IOException {
        try {
            var response = remoteClient.performRequest(new Request("DELETE", "/test*"));
            assertEquals(200, response.getStatusLine().getStatusCode());
        } catch (ResponseException re) {
            assertEquals(404, re.getResponse().getStatusLine().getStatusCode());
        }
    }

    private MapMatcher getClustersMetadataMatcher() {
        MapMatcher mapMatcher = matchesMap();
        mapMatcher = mapMatcher.entry("running", 0);
        mapMatcher = mapMatcher.entry("total", 1);
        mapMatcher = mapMatcher.entry("failed", 0);
        mapMatcher = mapMatcher.entry("partial", 0);
        mapMatcher = mapMatcher.entry("successful", 1);
        mapMatcher = mapMatcher.entry("skipped", 0);
        mapMatcher = mapMatcher.entry(
            "details",
            matchesMap().entry(
                Clusters.REMOTE_CLUSTER_NAME,
                matchesMap().entry("_shards", matchesMap().extraOk())
                    .entry("took", greaterThanOrEqualTo(0))
                    .entry("indices", instanceOf(String.class))
                    .entry("status", "successful")
            )
        );
        return mapMatcher;
    }

    @Override
    protected void assertQueryResult(Map<String, Object> result, Matcher<?> columnMatcher, Matcher<?> valuesMatcher) {
        var matcher = getResultMatcher(result).entry("columns", columnMatcher).entry("values", valuesMatcher);
        if (isCCSRequest) {
            matcher = matcher.entry("_clusters", getClustersMetadataMatcher());
        }
        assertMap(result, matcher);
    }

    private static boolean checkVersion(org.elasticsearch.Version version) {
        return version.onOrAfter(Version.fromString("9.1.0"))
            || (version.onOrAfter(Version.fromString("8.19.0")) && version.before(Version.fromString("9.0.0")));
    }

    public void testIndicesDontExistWithRemoteLookupJoin() throws IOException {
        assumeTrue("Only works with remote LOOKUP JOIN support", EsqlCapabilities.Cap.ENABLE_LOOKUP_JOIN_ON_REMOTE.isEnabled());
        // This check is for "local" cluster - which is different from test runner actually, so it could be old
        assumeTrue(
            "Only works with remote LOOKUP JOIN support",
            clusterHasCapability(
                client(),
                "POST",
                "_query",
                List.of(),
                List.of(EsqlCapabilities.Cap.ENABLE_LOOKUP_JOIN_ON_REMOTE.capabilityName())
            ).orElse(false)
        );

        int docsTest1 = randomIntBetween(1, 5);
        indexTimestampData(docsTest1, "test1", "2024-11-26", "id1");

        var pattern = "FROM test1,*:test1";
        ResponseException e = expectThrows(
            ResponseException.class,
            () -> runEsql(timestampFilter("gte", "2020-01-01").query(pattern + " | LOOKUP JOIN foo ON id1"))
        );
        assertEquals(400, e.getResponse().getStatusLine().getStatusCode());
        assertThat(
            e.getMessage(),
            allOf(containsString("verification_exception"), containsString("Unknown index [foo,remote_cluster:foo]"))
        );
    }

    // We need a separate test since remote missing indices and local missing indices now work differently
    public void testIndicesDontExistRemote() throws IOException {
        // Exclude old versions
        assumeTrue("Only works with latest support_unavailable logic", checkVersion(Clusters.localClusterVersion()));
        int docsTest1 = randomIntBetween(1, 5);
        indexTimestampData(docsTest1, "test1", "2024-11-26", "id1");

        Map<String, Object> result = runEsql(
            timestampFilter("gte", "2020-01-01").query("FROM *:foo,*:test1 METADATA _index | SORT id1 | KEEP _index, id*"),
            false
        );

        // `foo` index doesn't exist, so the request will currently be successful, but with partial results
        var isPartial = result.get("is_partial");
        assertThat(isPartial, is(true));
        assertThat(
            result,
            matchesMap().entry(
                "_clusters",
                matchesMap().entry(
                    "details",
                    matchesMap().entry(
                        "remote_cluster",
                        matchesMap().entry(
                            "failures",
                            matchesList().item(
                                matchesMap().entry("reason", matchesMap().entry("reason", "no such index [foo]").extraOk()).extraOk()
                            )
                        ).extraOk()
                    ).extraOk()
                ).extraOk()
            ).extraOk()
        );
        @SuppressWarnings("unchecked")
        var columns = (List<List<Object>>) result.get("columns");
        assertThat(
            columns,
            matchesList().item(matchesMap().entry("name", "_index").entry("type", "keyword"))
                .item(matchesMap().entry("name", "id1").entry("type", "integer"))
        );
        @SuppressWarnings("unchecked")
        var values = (List<List<Object>>) result.get("values");
        // TODO: for now, we return empty result, but eventually it should return records from test1
        assertThat(values, hasSize(0));

    }
}
