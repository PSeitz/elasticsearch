/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.search;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.TermsLookup;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.metrics.InternalStats;
import org.elasticsearch.search.vectors.KnnSearchBuilder;
import org.elasticsearch.search.vectors.KnnVectorQueryBuilder;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class KnnSearchSingleNodeTests extends ESSingleNodeTestCase {
    private static final int VECTOR_DIMENSION = 10;

    public void testKnnWithQuery() throws IOException {
        int numShards = 1 + randomInt(3);
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShards).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("text")
            .field("type", "text")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index", indexSettings, builder);

        for (int doc = 0; doc < 10; doc++) {
            client().prepareIndex("index").setSource("vector", randomVector(), "text", "hello world").get();
            client().prepareIndex("index").setSource("text", "goodnight world").get();
        }

        client().admin().indices().prepareRefresh("index").get();

        float[] queryVector = randomVector();
        KnnSearchBuilder knnSearch = new KnnSearchBuilder("vector", queryVector, 5, 50).boost(5.0f);
        SearchResponse response = client().prepareSearch("index")
            .setKnnSearch(List.of(knnSearch))
            .setQuery(QueryBuilders.matchQuery("text", "goodnight"))
            .addFetchField("*")
            .setSize(10)
            .get();

        // The total hits is k plus the number of text matches
        assertHitCount(response, 15);
        assertEquals(10, response.getHits().getHits().length);

        // Because of the boost, vector results should appear first
        assertNotNull(response.getHits().getAt(0).field("vector"));
    }

    public void testKnnFilter() throws IOException {
        int numShards = 1 + randomInt(3);
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShards).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("field")
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index", indexSettings, builder);

        for (int doc = 0; doc < 10; doc++) {
            String value = doc % 2 == 0 ? "first" : "second";
            client().prepareIndex("index").setId(String.valueOf(doc)).setSource("vector", randomVector(), "field", value).get();
        }

        client().admin().indices().prepareRefresh("index").get();

        float[] queryVector = randomVector();
        KnnSearchBuilder knnSearch = new KnnSearchBuilder("vector", queryVector, 5, 50).addFilterQuery(
            QueryBuilders.termsQuery("field", "second")
        );
        SearchResponse response = client().prepareSearch("index").setKnnSearch(List.of(knnSearch)).addFetchField("*").setSize(10).get();

        assertHitCount(response, 5);
        assertEquals(5, response.getHits().getHits().length);
        for (SearchHit hit : response.getHits().getHits()) {
            assertEquals("second", hit.field("field").getValue());
        }
    }

    public void testKnnFilterWithRewrite() throws IOException {
        int numShards = 1 + randomInt(3);
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShards).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("field")
            .field("type", "keyword")
            .endObject()
            .startObject("other-field")
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index", indexSettings, builder);

        for (int doc = 0; doc < 10; doc++) {
            client().prepareIndex("index").setId(String.valueOf(doc)).setSource("vector", randomVector(), "field", "value").get();
        }
        client().prepareIndex("index").setId("lookup-doc").setSource("other-field", "value").get();

        client().admin().indices().prepareRefresh("index").get();

        float[] queryVector = randomVector();
        KnnSearchBuilder knnSearch = new KnnSearchBuilder("vector", queryVector, 5, 50).addFilterQuery(
            QueryBuilders.termsLookupQuery("field", new TermsLookup("index", "lookup-doc", "other-field"))
        );
        SearchResponse response = client().prepareSearch("index").setKnnSearch(List.of(knnSearch)).setSize(10).get();

        assertHitCount(response, 5);
        assertEquals(5, response.getHits().getHits().length);
    }

    public void testMultiKnnClauses() throws IOException {
        // This tests the recall from vectors being searched in different docs
        int numShards = 1 + randomInt(3);
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShards).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("vector_2")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("text")
            .field("type", "text")
            .endObject()
            .startObject("number")
            .field("type", "long")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index", indexSettings, builder);

        for (int doc = 0; doc < 10; doc++) {
            client().prepareIndex("index").setSource("vector", randomVector(), "text", "hello world", "number", 1).get();
            client().prepareIndex("index").setSource("vector_2", randomVector(), "text", "hello world", "number", 2).get();
            client().prepareIndex("index").setSource("text", "goodnight world", "number", 3).get();
        }
        client().admin().indices().prepareRefresh("index").get();

        float[] queryVector = randomVector();
        KnnSearchBuilder knnSearch = new KnnSearchBuilder("vector", queryVector, 5, 50).boost(5.0f);
        KnnSearchBuilder knnSearch2 = new KnnSearchBuilder("vector_2", queryVector, 5, 50).boost(10.0f);
        SearchResponse response = client().prepareSearch("index")
            .setKnnSearch(List.of(knnSearch, knnSearch2))
            .setQuery(QueryBuilders.matchQuery("text", "goodnight"))
            .addFetchField("*")
            .setSize(10)
            .addAggregation(AggregationBuilders.stats("stats").field("number"))
            .get();

        // The total hits is k plus the number of text matches
        assertHitCount(response, 20);
        assertEquals(10, response.getHits().getHits().length);
        InternalStats agg = response.getAggregations().get("stats");
        assertThat(agg.getCount(), equalTo(20L));
        assertThat(agg.getMax(), equalTo(3.0));
        assertThat(agg.getMin(), equalTo(1.0));
        assertThat(agg.getAvg(), equalTo(2.25));
        assertThat(agg.getSum(), equalTo(45.0));

        // Because of the boost, vector_2 results should appear first
        assertNotNull(response.getHits().getAt(0).field("vector_2"));
    }

    public void testMultiKnnClausesSameDoc() throws IOException {
        int numShards = 1 + randomInt(3);
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShards).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("vector_2")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("number")
            .field("type", "long")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index", indexSettings, builder);

        for (int doc = 0; doc < 10; doc++) {
            // Make them have hte same vector. This will allow us to test the recall is the same but scores take into account both fields
            float[] vector = randomVector();
            client().prepareIndex("index").setSource("vector", vector, "vector_2", vector, "number", doc).get();
        }
        client().admin().indices().prepareRefresh("index").get();

        float[] queryVector = randomVector();
        // Having the same query vector and same docs should mean our KNN scores are linearly combined if the same doc is matched
        KnnSearchBuilder knnSearch = new KnnSearchBuilder("vector", queryVector, 5, 50);
        KnnSearchBuilder knnSearch2 = new KnnSearchBuilder("vector_2", queryVector, 5, 50);
        SearchResponse responseOneKnn = client().prepareSearch("index")
            .setKnnSearch(List.of(knnSearch))
            .addFetchField("*")
            .setSize(10)
            .addAggregation(AggregationBuilders.stats("stats").field("number"))
            .get();
        SearchResponse responseBothKnn = client().prepareSearch("index")
            .setKnnSearch(List.of(knnSearch, knnSearch2))
            .addFetchField("*")
            .setSize(10)
            .addAggregation(AggregationBuilders.stats("stats").field("number"))
            .get();

        // The total hits is k matched docs
        assertHitCount(responseOneKnn, 5);
        assertHitCount(responseBothKnn, 5);
        assertEquals(5, responseOneKnn.getHits().getHits().length);
        assertEquals(5, responseBothKnn.getHits().getHits().length);

        for (int i = 0; i < responseOneKnn.getHits().getHits().length; i++) {
            SearchHit oneHit = responseOneKnn.getHits().getHits()[i];
            SearchHit bothHit = responseBothKnn.getHits().getHits()[i];
            assertThat(bothHit.getId(), equalTo(oneHit.getId()));
            assertThat(bothHit.getScore(), greaterThan(oneHit.getScore()));
        }
        InternalStats oneAgg = responseOneKnn.getAggregations().get("stats");
        InternalStats bothAgg = responseBothKnn.getAggregations().get("stats");
        assertThat(bothAgg.getCount(), equalTo(oneAgg.getCount()));
        assertThat(bothAgg.getAvg(), equalTo(oneAgg.getAvg()));
        assertThat(bothAgg.getMax(), equalTo(oneAgg.getMax()));
        assertThat(bothAgg.getSum(), equalTo(oneAgg.getSum()));
        assertThat(bothAgg.getMin(), equalTo(oneAgg.getMin()));
    }

    public void testKnnFilteredAlias() throws IOException {
        int numShards = 1 + randomInt(3);
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShards).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .startObject("field")
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index", indexSettings, builder);
        client().admin().indices().prepareAliases().addAlias("index", "test-alias", QueryBuilders.termQuery("field", "hit")).get();

        int expectedHits = 0;
        for (int doc = 0; doc < 10; doc++) {
            if (randomBoolean()) {
                client().prepareIndex("index").setId(String.valueOf(doc)).setSource("vector", randomVector(), "field", "hit").get();
                ++expectedHits;
            } else {
                client().prepareIndex("index").setId(String.valueOf(doc)).setSource("vector", randomVector(), "field", "not hit").get();
            }
        }
        client().admin().indices().prepareRefresh("index").get();

        float[] queryVector = randomVector();
        KnnSearchBuilder knnSearch = new KnnSearchBuilder("vector", queryVector, 10, 50);
        SearchResponse response = client().prepareSearch("test-alias").setKnnSearch(List.of(knnSearch)).setSize(10).get();

        assertHitCount(response, expectedHits);
        assertEquals(expectedHits, response.getHits().getHits().length);
    }

    public void testKnnSearchAction() throws IOException {
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1).build();
        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject("vector")
            .field("type", "dense_vector")
            .field("dims", VECTOR_DIMENSION)
            .field("index", true)
            .field("similarity", "l2_norm")
            .endObject()
            .endObject()
            .endObject();
        createIndex("index1", indexSettings, builder);
        createIndex("index2", indexSettings, builder);

        for (int doc = 0; doc < 10; doc++) {
            client().prepareIndex("index1").setId(String.valueOf(doc)).setSource("vector", randomVector()).get();
            client().prepareIndex("index2").setId(String.valueOf(doc)).setSource("vector", randomVector()).get();
        }

        client().admin().indices().prepareForceMerge("index1", "index2").setMaxNumSegments(1).get();
        client().admin().indices().prepareRefresh("index1", "index2").get();

        // Since there's no kNN search action at the transport layer, we just emulate
        // how the action works (it builds a kNN query under the hood)
        float[] queryVector = randomVector();
        SearchResponse response = client().prepareSearch("index1", "index2")
            .setQuery(new KnnVectorQueryBuilder("vector", queryVector, 5))
            .setSize(2)
            .get();

        // The total hits is num_cands * num_shards, since the query gathers num_cands hits from each shard
        assertHitCount(response, 5 * 2);
        assertEquals(2, response.getHits().getHits().length);
    }

    private float[] randomVector() {
        float[] vector = new float[VECTOR_DIMENSION];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = randomFloat();
        }
        return vector;
    }
}
