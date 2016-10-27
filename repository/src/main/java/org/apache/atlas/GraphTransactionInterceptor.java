/*
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
 */

package org.apache.atlas;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.atlas.repository.graph.AtlasGraphProvider;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.typesystem.exception.EntityNotFoundException;
import org.apache.atlas.typesystem.exception.SchemaNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphTransactionInterceptor implements MethodInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(GraphTransactionInterceptor.class);
    private AtlasGraph graph;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        
        if (graph == null) {
            graph = AtlasGraphProvider.getGraphInstance();
        }

        try {
            Object response = invocation.proceed();
            graph.commit();
            LOG.info("graph commit");
            return response;
        } catch (Throwable t) {
            if (logException(t)) {
                LOG.error("graph rollback due to exception ", t);
            } else {
                LOG.error("graph rollback due to exception " + t.getClass().getSimpleName() + ":" + t.getMessage());
            }
            graph.rollback();
            throw t;
        }
    }

    boolean logException(Throwable t) {
        if ((t instanceof SchemaNotFoundException) || (t instanceof EntityNotFoundException)) {
            return false;
        }
        return true;
    }
}
