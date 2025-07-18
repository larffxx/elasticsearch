/*
 * @notice
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications copyright (C) 2024 Elasticsearch B.V.
 */
package org.elasticsearch.index.codec.vectors.es818;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.misc.store.DirectIODirectory;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.tests.index.BaseKnnVectorsFormatTestCase;
import org.apache.lucene.tests.store.MockDirectoryWrapper;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.SameThreadExecutorService;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.codec.vectors.reflect.OffHeapByteSizeUtils;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.index.store.FsDirectoryFactory;
import org.elasticsearch.test.IndexSettingsModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;

import static java.lang.String.format;
import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;
import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

public class ES818HnswBinaryQuantizedVectorsFormatTests extends BaseKnnVectorsFormatTestCase {

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging(); // native access requires logging to be initialized
    }

    static final Codec codec = TestUtil.alwaysKnnVectorsFormat(new ES818HnswBinaryQuantizedVectorsFormat());

    @Override
    protected Codec getCodec() {
        return codec;
    }

    public void testToString() {
        FilterCodec customCodec = new FilterCodec("foo", Codec.getDefault()) {
            @Override
            public KnnVectorsFormat knnVectorsFormat() {
                return new ES818HnswBinaryQuantizedVectorsFormat(10, 20, 1, null);
            }
        };
        String expectedPattern =
            "ES818HnswBinaryQuantizedVectorsFormat(name=ES818HnswBinaryQuantizedVectorsFormat, maxConn=10, beamWidth=20,"
                + " flatVectorFormat=ES818BinaryQuantizedVectorsFormat(name=ES818BinaryQuantizedVectorsFormat,"
                + " flatVectorScorer=ES818BinaryFlatVectorsScorer(nonQuantizedDelegate=%s())))";

        var defaultScorer = format(Locale.ROOT, expectedPattern, "DefaultFlatVectorScorer");
        var memSegScorer = format(Locale.ROOT, expectedPattern, "Lucene99MemorySegmentFlatVectorsScorer");
        assertThat(customCodec.knnVectorsFormat().toString(), is(oneOf(defaultScorer, memSegScorer)));
    }

    public void testSingleVectorCase() throws Exception {
        float[] vector = randomVector(random().nextInt(12, 500));
        for (VectorSimilarityFunction similarityFunction : VectorSimilarityFunction.values()) {
            try (Directory dir = newDirectory(); IndexWriter w = new IndexWriter(dir, newIndexWriterConfig())) {
                Document doc = new Document();
                doc.add(new KnnFloatVectorField("f", vector, similarityFunction));
                w.addDocument(doc);
                w.commit();
                try (IndexReader reader = DirectoryReader.open(w)) {
                    LeafReader r = getOnlyLeafReader(reader);
                    FloatVectorValues vectorValues = r.getFloatVectorValues("f");
                    KnnVectorValues.DocIndexIterator docIndexIterator = vectorValues.iterator();
                    assert (vectorValues.size() == 1);
                    while (docIndexIterator.nextDoc() != NO_MORE_DOCS) {
                        assertArrayEquals(vector, vectorValues.vectorValue(docIndexIterator.index()), 0.00001f);
                    }
                    float[] randomVector = randomVector(vector.length);
                    float trueScore = similarityFunction.compare(vector, randomVector);
                    TopDocs td = r.searchNearestVectors("f", randomVector, 1, null, Integer.MAX_VALUE);
                    assertEquals(1, td.totalHits.value());
                    assertTrue(td.scoreDocs[0].score >= 0);
                    // When it's the only vector in a segment, the score should be very close to the true score
                    assertEquals(trueScore, td.scoreDocs[0].score, 0.01f);
                }
            }
        }
    }

    public void testLimits() {
        expectThrows(IllegalArgumentException.class, () -> new ES818HnswBinaryQuantizedVectorsFormat(-1, 20));
        expectThrows(IllegalArgumentException.class, () -> new ES818HnswBinaryQuantizedVectorsFormat(0, 20));
        expectThrows(IllegalArgumentException.class, () -> new ES818HnswBinaryQuantizedVectorsFormat(20, 0));
        expectThrows(IllegalArgumentException.class, () -> new ES818HnswBinaryQuantizedVectorsFormat(20, -1));
        expectThrows(IllegalArgumentException.class, () -> new ES818HnswBinaryQuantizedVectorsFormat(512 + 1, 20));
        expectThrows(IllegalArgumentException.class, () -> new ES818HnswBinaryQuantizedVectorsFormat(20, 3201));
        expectThrows(
            IllegalArgumentException.class,
            () -> new ES818HnswBinaryQuantizedVectorsFormat(20, 100, 1, new SameThreadExecutorService())
        );
    }

    // Ensures that all expected vector similarity functions are translatable in the format.
    public void testVectorSimilarityFuncs() {
        // This does not necessarily have to be all similarity functions, but
        // differences should be considered carefully.
        var expectedValues = Arrays.stream(VectorSimilarityFunction.values()).toList();
        assertEquals(Lucene99HnswVectorsReader.SIMILARITY_FUNCTIONS, expectedValues);
    }

    public void testSimpleOffHeapSize() throws IOException {
        try (Directory dir = newDirectory()) {
            testSimpleOffHeapSizeImpl(dir, newIndexWriterConfig(), true);
        }
    }

    public void testSimpleOffHeapSizeFSDir() throws IOException {
        checkDirectIOSupported();
        var config = newIndexWriterConfig().setUseCompoundFile(false); // avoid compound files to allow directIO
        try (Directory dir = newFSDirectory()) {
            testSimpleOffHeapSizeImpl(dir, config, false);
        }
    }

    public void testSimpleOffHeapSizeMMapDir() throws IOException {
        try (Directory dir = newMMapDirectory()) {
            testSimpleOffHeapSizeImpl(dir, newIndexWriterConfig(), true);
        }
    }

    public void testSimpleOffHeapSizeImpl(Directory dir, IndexWriterConfig config, boolean expectVecOffHeap) throws IOException {
        float[] vector = randomVector(random().nextInt(12, 500));
        try (IndexWriter w = new IndexWriter(dir, config)) {
            Document doc = new Document();
            doc.add(new KnnFloatVectorField("f", vector, DOT_PRODUCT));
            w.addDocument(doc);
            w.commit();
            try (IndexReader reader = DirectoryReader.open(w)) {
                LeafReader r = getOnlyLeafReader(reader);
                if (r instanceof CodecReader codecReader) {
                    KnnVectorsReader knnVectorsReader = codecReader.getVectorReader();
                    if (knnVectorsReader instanceof PerFieldKnnVectorsFormat.FieldsReader fieldsReader) {
                        knnVectorsReader = fieldsReader.getFieldReader("f");
                    }
                    var fieldInfo = r.getFieldInfos().fieldInfo("f");
                    var offHeap = OffHeapByteSizeUtils.getOffHeapByteSize(knnVectorsReader, fieldInfo);
                    assertEquals(expectVecOffHeap ? 3 : 2, offHeap.size());
                    assertEquals(1L, (long) offHeap.get("vex"));
                    assertTrue(offHeap.get("veb") > 0L);
                    if (expectVecOffHeap) {
                        assertEquals(vector.length * Float.BYTES, (long) offHeap.get("vec"));
                    }
                }
            }
        }
    }

    static Directory newMMapDirectory() throws IOException {
        Directory dir = new MMapDirectory(createTempDir("ES818BinaryQuantizedVectorsFormatTests"));
        if (random().nextBoolean()) {
            dir = new MockDirectoryWrapper(random(), dir);
        }
        return dir;
    }

    private Directory newFSDirectory() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexModule.INDEX_STORE_TYPE_SETTING.getKey(), IndexModule.Type.HYBRIDFS.name().toLowerCase(Locale.ROOT))
            .build();
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("foo", settings);
        Path tempDir = createTempDir().resolve(idxSettings.getUUID()).resolve("0");
        Files.createDirectories(tempDir);
        ShardPath path = new ShardPath(false, tempDir, tempDir, new ShardId(idxSettings.getIndex(), 0));
        Directory dir = (new FsDirectoryFactory()).newDirectory(idxSettings, path);
        if (random().nextBoolean()) {
            dir = new MockDirectoryWrapper(random(), dir);
        }
        return dir;
    }

    static void checkDirectIOSupported() {
        assumeTrue("Direct IO is not enabled", ES818BinaryQuantizedVectorsFormat.USE_DIRECT_IO);

        Path path = createTempDir("directIOProbe");
        try (Directory dir = open(path); IndexOutput out = dir.createOutput("out", IOContext.DEFAULT)) {
            out.writeString("test");
        } catch (IOException e) {
            assumeNoException("test requires a filesystem that supports Direct IO", e);
        }
    }

    static DirectIODirectory open(Path path) throws IOException {
        return new DirectIODirectory(FSDirectory.open(path)) {
            @Override
            protected boolean useDirectIO(String name, IOContext context, OptionalLong fileLength) {
                return true;
            }
        };
    }
}
