/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 *
 * this file was contributed to by a Generative AI
 */

package org.elasticsearch.xpack.inference.action;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.inference.InferenceServiceRegistry;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.UnparsedModel;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.inference.action.DeleteInferenceEndpointAction;
import org.elasticsearch.xpack.core.ml.utils.InferenceProcessorInfoExtractor;
import org.elasticsearch.xpack.inference.common.InferenceExceptions;
import org.elasticsearch.xpack.inference.registry.ModelRegistry;

import java.util.Set;
import java.util.concurrent.Executor;

import static org.elasticsearch.xpack.core.ml.utils.SemanticTextInfoExtractor.extractIndexesReferencingInferenceEndpoints;
import static org.elasticsearch.xpack.inference.InferencePlugin.UTILITY_THREAD_POOL_NAME;

public class TransportDeleteInferenceEndpointAction extends TransportMasterNodeAction<
    DeleteInferenceEndpointAction.Request,
    DeleteInferenceEndpointAction.Response> {

    private final ModelRegistry modelRegistry;
    private final InferenceServiceRegistry serviceRegistry;
    private final Executor executor;
    private final ProjectResolver projectResolver;

    @Inject
    public TransportDeleteInferenceEndpointAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ModelRegistry modelRegistry,
        InferenceServiceRegistry serviceRegistry,
        ProjectResolver projectResolver
    ) {
        super(
            DeleteInferenceEndpointAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DeleteInferenceEndpointAction.Request::new,
            DeleteInferenceEndpointAction.Response::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.modelRegistry = modelRegistry;
        this.serviceRegistry = serviceRegistry;
        this.executor = threadPool.executor(UTILITY_THREAD_POOL_NAME);
        this.projectResolver = projectResolver;
    }

    @Override
    protected void masterOperation(
        Task task,
        DeleteInferenceEndpointAction.Request request,
        ClusterState state,
        ActionListener<DeleteInferenceEndpointAction.Response> masterListener
    ) {
        // workaround for https://github.com/elastic/elasticsearch/issues/97916 - TODO remove this when we can
        executor.execute(ActionRunnable.wrap(masterListener, l -> doExecuteForked(request, state, l)));
    }

    private void doExecuteForked(
        DeleteInferenceEndpointAction.Request request,
        ClusterState state,
        ActionListener<DeleteInferenceEndpointAction.Response> masterListener
    ) {
        SubscribableListener.<UnparsedModel>newForked(modelConfigListener -> {
            // Get the model from the registry

            modelRegistry.getModel(request.getInferenceEndpointId(), modelConfigListener);
        }).<Boolean>andThen((listener, unparsedModel) -> {
            // Validate the request & issue the stop request to the service

            if (request.getTaskType().isAnyOrSame(unparsedModel.taskType()) == false) {
                // specific task type in request does not match the models
                listener.onFailure(InferenceExceptions.mismatchedTaskTypeException(request.getTaskType(), unparsedModel.taskType()));
                return;
            }

            if (request.isDryRun()) {
                handleDryRun(request, state, masterListener);
                return;
            } else if (request.isForceDelete() == false) {
                var errorString = endpointIsReferencedInPipelinesOrIndexes(state, request.getInferenceEndpointId());
                if (errorString != null) {
                    listener.onFailure(new ElasticsearchStatusException(errorString, RestStatus.CONFLICT));
                    return;
                } else if (isInferenceIdReserved(request.getInferenceEndpointId())) {
                    listener.onFailure(
                        new ElasticsearchStatusException(
                            Strings.format(
                                "[%s] is a reserved inference endpoint. Use the force=true query parameter "
                                    + "to delete the inference endpoint.",
                                request.getInferenceEndpointId()
                            ),
                            RestStatus.BAD_REQUEST
                        )
                    );
                    return;
                }
            }

            var service = serviceRegistry.getService(unparsedModel.service());
            Model model;
            if (service.isPresent()) {
                try {
                    model = service.get()
                        .parsePersistedConfig(unparsedModel.inferenceEntityId(), unparsedModel.taskType(), unparsedModel.settings());
                } catch (Exception e) {
                    if (request.isForceDelete()) {
                        listener.onResponse(true);
                        return;
                    } else {
                        listener.onFailure(
                            new ElasticsearchStatusException(
                                Strings.format(
                                    "Failed to parse model configuration for inference endpoint [%s]",
                                    request.getInferenceEndpointId()
                                ),
                                RestStatus.INTERNAL_SERVER_ERROR,
                                e
                            )
                        );
                        return;
                    }
                }
                service.get().stop(model, listener.delegateResponse((l, e) -> {
                    if (request.isForceDelete()) {
                        l.onResponse(true);
                    } else {
                        l.onFailure(e);
                    }
                }));
            } else if (request.isForceDelete()) {
                listener.onResponse(true);
            } else {
                listener.onFailure(
                    new ElasticsearchStatusException(
                        "No service found for this inference endpoint " + request.getInferenceEndpointId(),
                        RestStatus.NOT_FOUND
                    )
                );
            }
        }).<Boolean>andThen((listener, didStop) -> {
            if (didStop) {
                modelRegistry.deleteModel(request.getInferenceEndpointId(), listener);
            } else {
                listener.onFailure(
                    new ElasticsearchStatusException(
                        "Failed to stop inference endpoint " + request.getInferenceEndpointId(),
                        RestStatus.INTERNAL_SERVER_ERROR
                    )
                );
            }
        })
            .addListener(
                masterListener.delegateFailure(
                    (l3, didDeleteModel) -> masterListener.onResponse(
                        new DeleteInferenceEndpointAction.Response(didDeleteModel, Set.of(), Set.of(), null)
                    )
                )
            );
    }

    private static void handleDryRun(
        DeleteInferenceEndpointAction.Request request,
        ClusterState state,
        ActionListener<DeleteInferenceEndpointAction.Response> masterListener
    ) {
        Set<String> pipelines = InferenceProcessorInfoExtractor.pipelineIdsForResource(state, Set.of(request.getInferenceEndpointId()));

        Set<String> indexesReferencedBySemanticText = extractIndexesReferencingInferenceEndpoints(
            state.getMetadata(),
            Set.of(request.getInferenceEndpointId())
        );

        masterListener.onResponse(
            new DeleteInferenceEndpointAction.Response(
                false,
                pipelines,
                indexesReferencedBySemanticText,
                buildErrorString(request.getInferenceEndpointId(), pipelines, indexesReferencedBySemanticText)
            )
        );
    }

    private static String endpointIsReferencedInPipelinesOrIndexes(final ClusterState state, final String inferenceEndpointId) {

        var pipelines = endpointIsReferencedInPipelines(state, inferenceEndpointId);
        var indexes = endpointIsReferencedInIndex(state, inferenceEndpointId);

        if (pipelines.isEmpty() == false || indexes.isEmpty() == false) {
            return buildErrorString(inferenceEndpointId, pipelines, indexes);
        }
        return null;
    }

    private boolean isInferenceIdReserved(String inferenceEndpointId) {
        return modelRegistry.containsDefaultConfigId(inferenceEndpointId);
    }

    private static String buildErrorString(String inferenceEndpointId, Set<String> pipelines, Set<String> indexes) {
        StringBuilder errorString = new StringBuilder();

        if (pipelines.isEmpty() == false) {
            errorString.append("Inference endpoint ")
                .append(inferenceEndpointId)
                .append(" is referenced by pipelines: ")
                .append(pipelines)
                .append(". ")
                .append("Ensure that no pipelines are using this inference endpoint, ")
                .append("or use force to ignore this warning and delete the inference endpoint.");
        }

        if (indexes.isEmpty() == false) {
            errorString.append(" Inference endpoint ")
                .append(inferenceEndpointId)
                .append(" is being used in the mapping for indexes: ")
                .append(indexes)
                .append(". ")
                .append("Ensure that no index mappings are using this inference endpoint, ")
                .append("or use force to ignore this warning and delete the inference endpoint.");
        }

        return errorString.toString();
    }

    private static Set<String> endpointIsReferencedInIndex(final ClusterState state, final String inferenceEndpointId) {
        return extractIndexesReferencingInferenceEndpoints(state.getMetadata(), Set.of(inferenceEndpointId));
    }

    private static Set<String> endpointIsReferencedInPipelines(final ClusterState state, final String inferenceEndpointId) {
        return InferenceProcessorInfoExtractor.pipelineIdsForResource(state, Set.of(inferenceEndpointId));
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteInferenceEndpointAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.WRITE);
    }

}
