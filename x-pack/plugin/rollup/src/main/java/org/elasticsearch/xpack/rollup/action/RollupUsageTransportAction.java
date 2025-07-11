/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.rollup.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.core.Predicates;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.protocol.xpack.XPackUsageRequest;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureResponse;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureTransportAction;
import org.elasticsearch.xpack.core.rollup.RollupFeatureSetUsage;
import org.elasticsearch.xpack.core.rollup.job.RollupJob;

public class RollupUsageTransportAction extends XPackUsageFeatureTransportAction {

    private final ProjectResolver projectResolver;

    @Inject
    public RollupUsageTransportAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ProjectResolver projectResolver
    ) {
        super(XPackUsageFeatureAction.ROLLUP.name(), transportService, clusterService, threadPool, actionFilters);
        this.projectResolver = projectResolver;
    }

    @Override
    protected void localClusterStateOperation(
        Task task,
        XPackUsageRequest request,
        ClusterState state,
        ActionListener<XPackUsageFeatureResponse> listener
    ) {
        int numberOfRollupJobs = findNumberOfRollupJobs(projectResolver.getProjectMetadata(state));
        RollupFeatureSetUsage usage = new RollupFeatureSetUsage(numberOfRollupJobs);
        listener.onResponse(new XPackUsageFeatureResponse(usage));
    }

    static int findNumberOfRollupJobs(ProjectMetadata project) {
        int numberOfRollupJobs = 0;
        PersistentTasksCustomMetadata persistentTasks = project.custom(PersistentTasksCustomMetadata.TYPE);
        if (persistentTasks != null) {
            numberOfRollupJobs = persistentTasks.findTasks(RollupJob.NAME, Predicates.always()).size();
        }
        return numberOfRollupJobs;
    }
}
