/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.get;

import org.apache.lucene.index.Term;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.lucene.uid.VersionsAndSeqNoResolver;
import org.elasticsearch.common.lucene.uid.VersionsAndSeqNoResolver.DocIdAndVersion;
import org.elasticsearch.common.metrics.CounterMetric;
import org.elasticsearch.common.metrics.MeanMetric;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.common.xcontent.XContentFieldFilter;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.fieldvisitor.CustomFieldsVisitor;
import org.elasticsearch.index.fieldvisitor.FieldsVisitor;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.shard.AbstractIndexShardComponent;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.index.seqno.SequenceNumbers.UNASSIGNED_PRIMARY_TERM;
import static org.elasticsearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO;

public final class ShardGetService extends AbstractIndexShardComponent {
    private final MapperService mapperService;
    private final MeanMetric existsMetric = new MeanMetric();
    private final MeanMetric missingMetric = new MeanMetric();
    private final CounterMetric currentMetric = new CounterMetric();
    private final IndexShard indexShard;

    public ShardGetService(IndexSettings indexSettings, IndexShard indexShard, MapperService mapperService) {
        super(indexShard.shardId(), indexSettings);
        this.mapperService = mapperService;
        this.indexShard = indexShard;
    }

    public GetStats stats() {
        return new GetStats(
            existsMetric.count(),
            TimeUnit.NANOSECONDS.toMillis(existsMetric.sum()),
            missingMetric.count(),
            TimeUnit.NANOSECONDS.toMillis(missingMetric.sum()),
            currentMetric.count()
        );
    }

    public GetResult get(
        String id,
        String[] gFields,
        boolean realtime,
        long version,
        VersionType versionType,
        FetchSourceContext fetchSourceContext
    ) throws IOException {
        return get(id, gFields, realtime, version, versionType, UNASSIGNED_SEQ_NO, UNASSIGNED_PRIMARY_TERM, fetchSourceContext);
    }

    private GetResult get(
        String id,
        String[] gFields,
        boolean realtime,
        long version,
        VersionType versionType,
        long ifSeqNo,
        long ifPrimaryTerm,
        FetchSourceContext fetchSourceContext
    ) throws IOException {
        currentMetric.inc();
        try {
            long now = System.nanoTime();
            GetResult getResult = innerGet(id, gFields, realtime, version, versionType, ifSeqNo, ifPrimaryTerm, fetchSourceContext);

            if (getResult.isExists()) {
                existsMetric.inc(System.nanoTime() - now);
            } else {
                missingMetric.inc(System.nanoTime() - now);
            }
            return getResult;
        } finally {
            currentMetric.dec();
        }
    }

    public GetResult getForUpdate(String id, long ifSeqNo, long ifPrimaryTerm) throws IOException {
        return get(
            id,
            new String[] { RoutingFieldMapper.NAME },
            true,
            Versions.MATCH_ANY,
            VersionType.INTERNAL,
            ifSeqNo,
            ifPrimaryTerm,
            FetchSourceContext.FETCH_SOURCE
        );
    }

    /**
     * Returns {@link GetResult} based on the specified {@link org.elasticsearch.index.engine.Engine.GetResult} argument.
     * This method basically loads specified fields for the associated document in the engineGetResult.
     * This method load the fields from the Lucene index and not from transaction log and therefore isn't realtime.
     * <p>
     * Note: Call <b>must</b> release engine searcher associated with engineGetResult!
     */
    public GetResult get(Engine.GetResult engineGetResult, String id, String[] fields, FetchSourceContext fetchSourceContext)
        throws IOException {
        if (engineGetResult.exists() == false) {
            return new GetResult(shardId.getIndexName(), id, UNASSIGNED_SEQ_NO, UNASSIGNED_PRIMARY_TERM, -1, false, null, null, null);
        }

        currentMetric.inc();
        try {
            long now = System.nanoTime();
            fetchSourceContext = normalizeFetchSourceContent(fetchSourceContext, fields);
            GetResult getResult = innerGetLoadFromStoredFields(id, fields, fetchSourceContext, engineGetResult);
            if (getResult.isExists()) {
                existsMetric.inc(System.nanoTime() - now);
            } else {
                missingMetric.inc(System.nanoTime() - now); // This shouldn't happen...
            }
            return getResult;
        } finally {
            currentMetric.dec();
        }
    }

    /**
     * decides what needs to be done based on the request input and always returns a valid non-null FetchSourceContext
     */
    private static FetchSourceContext normalizeFetchSourceContent(@Nullable FetchSourceContext context, @Nullable String[] gFields) {
        if (context != null) {
            return context;
        }
        if (gFields == null) {
            return FetchSourceContext.FETCH_SOURCE;
        }
        for (String field : gFields) {
            if (SourceFieldMapper.NAME.equals(field)) {
                return FetchSourceContext.FETCH_SOURCE;
            }
        }
        return FetchSourceContext.DO_NOT_FETCH_SOURCE;
    }

    private GetResult innerGet(
        String id,
        String[] gFields,
        boolean realtime,
        long version,
        VersionType versionType,
        long ifSeqNo,
        long ifPrimaryTerm,
        FetchSourceContext fetchSourceContext
    ) throws IOException {
        fetchSourceContext = normalizeFetchSourceContent(fetchSourceContext, gFields);

        long now = System.nanoTime();
        Engine.GetResult get = indexShard.get(
            new Engine.Get(realtime, realtime, id).version(version)
                .versionType(versionType)
                .setIfSeqNo(ifSeqNo)
                .setIfPrimaryTerm(ifPrimaryTerm)
        );
        if (get.exists() == false) {
            get.close();
        }

        if (get == null || get.exists() == false) {
            return new GetResult(shardId.getIndexName(), id, UNASSIGNED_SEQ_NO, UNASSIGNED_PRIMARY_TERM, -1, false, null, null, null);
        }

        try {
            // break between having loaded it from translog (so we only have _source), and having a document to load
            return innerGetLoadFromStoredFields(id, gFields, fetchSourceContext, get);
        } finally {
            get.close();
        }
    }

    public GetResult getFromSearcher(Engine.Searcher searcher, String id, String[] gFields, FetchSourceContext fetchSourceContext)
        throws IOException {
        currentMetric.inc();
        try {
            fetchSourceContext = normalizeFetchSourceContent(fetchSourceContext, gFields);
            final long now = System.nanoTime();
            final DocIdAndVersion docIdAndVersion = VersionsAndSeqNoResolver.lookupId(
                searcher.getDirectoryReader(),
                new Term(IdFieldMapper.NAME, Uid.encodeId(id))
            );
            if (docIdAndVersion == null) {
                missingMetric.inc(System.nanoTime() - now);
                return new GetResult(shardId.getIndexName(), id, UNASSIGNED_SEQ_NO, UNASSIGNED_PRIMARY_TERM, -1, false, null, null, null);
            }
            final Engine.GetResult get = new Engine.GetResult(searcher, docIdAndVersion);
            GetResult getResult = innerGetLoadFromStoredFields(id, gFields, fetchSourceContext, get);
            existsMetric.inc(System.nanoTime() - now);
            return getResult;
        } finally {
            currentMetric.dec();
        }
    }

    private GetResult innerGetLoadFromStoredFields(
        String id,
        String[] storedFields,
        FetchSourceContext fetchSourceContext,
        Engine.GetResult get
    ) throws IOException {
        assert get.exists() : "method should only be called if document could be retrieved";

        // check first if stored fields to be loaded don't contain an object field
        MappingLookup mappingLookup = mapperService.mappingLookup();
        if (storedFields != null) {
            for (String field : storedFields) {
                Mapper fieldMapper = mappingLookup.getMapper(field);
                if (fieldMapper == null) {
                    if (mappingLookup.objectMappers().get(field) != null) {
                        // Only fail if we know it is a object field, missing paths / fields shouldn't fail.
                        throw new IllegalArgumentException("field [" + field + "] isn't a leaf field");
                    }
                }
            }
        }

        Map<String, DocumentField> documentFields = null;
        Map<String, DocumentField> metadataFields = null;
        BytesReference source = null;
        DocIdAndVersion docIdAndVersion = get.docIdAndVersion();
        FieldsVisitor fieldVisitor = buildFieldsVisitors(storedFields, fetchSourceContext);
        if (fieldVisitor != null) {
            try {
                docIdAndVersion.reader.document(docIdAndVersion.docId, fieldVisitor);
            } catch (IOException e) {
                throw new ElasticsearchException("Failed to get id [" + id + "]", e);
            }
            source = mappingLookup.newSourceLoader().leaf(docIdAndVersion.reader).source(fieldVisitor, docIdAndVersion.docId);

            // put stored fields into result objects
            if (fieldVisitor.fields().isEmpty() == false) {
                fieldVisitor.postProcess(mapperService::fieldType);
                documentFields = new HashMap<>();
                metadataFields = new HashMap<>();
                for (Map.Entry<String, List<Object>> entry : fieldVisitor.fields().entrySet()) {
                    if (mapperService.isMetadataField(entry.getKey())) {
                        metadataFields.put(entry.getKey(), new DocumentField(entry.getKey(), entry.getValue()));
                    } else {
                        documentFields.put(entry.getKey(), new DocumentField(entry.getKey(), entry.getValue()));
                    }
                }
            }
        }

        if (source != null) {
            // apply request-level source filtering
            if (fetchSourceContext.fetchSource() == false) {
                source = null;
            } else if (fetchSourceContext.includes().length > 0 || fetchSourceContext.excludes().length > 0) {
                try {
                    source = XContentFieldFilter.newFieldFilter(fetchSourceContext.includes(), fetchSourceContext.excludes())
                        .apply(source, null);
                } catch (IOException e) {
                    throw new ElasticsearchException("Failed to get id [" + id + "] with includes/excludes set", e);
                }
            }
        }

        return new GetResult(
            shardId.getIndexName(),
            id,
            get.docIdAndVersion().seqNo,
            get.docIdAndVersion().primaryTerm,
            get.version(),
            get.exists(),
            source,
            documentFields,
            metadataFields
        );
    }

    private static FieldsVisitor buildFieldsVisitors(String[] fields, FetchSourceContext fetchSourceContext) {
        if (fields == null || fields.length == 0) {
            return fetchSourceContext.fetchSource() ? new FieldsVisitor(true) : null;
        }

        return new CustomFieldsVisitor(Sets.newHashSet(fields), fetchSourceContext.fetchSource());
    }
}
